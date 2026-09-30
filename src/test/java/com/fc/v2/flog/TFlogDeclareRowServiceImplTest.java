package com.fc.v2.flog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.DuplicateKeyException;

import com.fc.v2.mapper.auto.TFlogDeclareBatchMapper;
import com.fc.v2.mapper.auto.TFlogDeclareRowMapper;
import com.fc.v2.model.auto.TFlogDeclareBatch;
import com.fc.v2.model.auto.TFlogDeclareRow;
import com.fc.v2.service.impl.TFlogDeclareRowServiceImpl;

/**
 * 伐区申报清册整批核收口径测试：封顶、逐行容错、批内去重、重发幂等、并发重发、计数同源。
 */
class TFlogDeclareRowServiceImplTest {

    @Mock
    private TFlogDeclareRowMapper rowMapper;

    @Mock
    private TFlogDeclareBatchMapper batchMapper;

    @InjectMocks
    private TFlogDeclareRowServiceImpl service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    private static TFlogDeclareRow row(String code, String qty) {
        TFlogDeclareRow r = new TFlogDeclareRow();
        r.setItemCode(code);
        if (qty != null) {
            r.setQty(new BigDecimal(qty));
        }
        return r;
    }

    private static List<TFlogDeclareRow> capturedRows(ArgumentCaptor<TFlogDeclareRow> cap) {
        return cap.getAllValues();
    }

    @Test
    void emptyBatch_returns0_andWritesNothing() {
        assertEquals(0, service.submitBatch("  ", java.util.Collections.singletonList(row("A", "1"))));
        assertEquals(0, service.submitBatch("B1", null));
        assertEquals(0, service.submitBatch("B1", new ArrayList<TFlogDeclareRow>()));
        verify(batchMapper, never()).insert(any());
        verify(rowMapper, never()).insert(any());
    }

    @Test
    void overLimit_returnsMinus1_andWholeBatchRejected() {
        List<TFlogDeclareRow> huge = new ArrayList<TFlogDeclareRow>();
        for (int i = 0; i < TFlogDeclareRowServiceImpl.BATCH_ROW_MAX + 1; i++) {
            huge.add(new TFlogDeclareRow());
        }
        assertEquals(-1, service.submitBatch("BIG", huge));
        verify(batchMapper, never()).insert(any());
        verify(rowMapper, never()).insert(any());
    }

    @Test
    void oneBadRow_doesNotRejectWholeBatch_andCountsShareOneSource() {
        TFlogDeclareRow a = row("A", "10");
        TFlogDeclareRow bad = row("B", null);
        TFlogDeclareRow c = row("C", "20");

        int ok = service.submitBatch("B1", java.util.Arrays.asList(a, bad, c));

        assertEquals(2, ok, "坏行挂起，好行照常核收，返回成功行数");

        ArgumentCaptor<TFlogDeclareBatch> headCap = ArgumentCaptor.forClass(TFlogDeclareBatch.class);
        verify(batchMapper).insert(headCap.capture());
        TFlogDeclareBatch head = headCap.getValue();
        assertEquals(3, head.getTotalCount().intValue());
        assertEquals(2, head.getOkCount().intValue());
        assertEquals(1, head.getFailCount().intValue());
        // 汇总与明细同源：成功+挂起=总数=返回口径
        assertEquals(head.getTotalCount().intValue(),
                head.getOkCount() + head.getFailCount());

        ArgumentCaptor<TFlogDeclareRow> rowCap = ArgumentCaptor.forClass(TFlogDeclareRow.class);
        verify(rowMapper, times(3)).insert(rowCap.capture());
        List<TFlogDeclareRow> rows = capturedRows(rowCap);
        int okCnt = 0, failCnt = 0;
        for (TFlogDeclareRow r : rows) {
            assertEquals("B1", r.getBatchNo());
            if (r.getStatus() == 1) {
                okCnt++;
            } else {
                assertEquals(2, r.getStatus().intValue());
                assertTrue(r.getRemark().contains("数量缺失"));
                failCnt++;
            }
        }
        assertEquals(2, okCnt);
        assertEquals(1, failCnt);
    }

    @Test
    void duplicateItemCodeInBatch_onlyFirstLanded_restHanged() {
        int ok = service.submitBatch("B2",
                java.util.Arrays.asList(row("DUP", "1"), row("DUP", "2"), row("X", "3")));

        assertEquals(2, ok);
        ArgumentCaptor<TFlogDeclareBatch> headCap = ArgumentCaptor.forClass(TFlogDeclareBatch.class);
        verify(batchMapper).insert(headCap.capture());
        assertEquals(3, headCap.getValue().getTotalCount().intValue());
        assertEquals(1, headCap.getValue().getFailCount().intValue());

        ArgumentCaptor<TFlogDeclareRow> rowCap = ArgumentCaptor.forClass(TFlogDeclareRow.class);
        verify(rowMapper, times(3)).insert(rowCap.capture());
        boolean dupHanged = false;
        for (TFlogDeclareRow r : rowCap.getAllValues()) {
            if (r.getStatus() == 2 && r.getRemark() != null && r.getRemark().contains("批内明细码重复")) {
                dupHanged = true;
            }
        }
        assertTrue(dupHanged, "同码第二行必须挂起并注明批内重复");
    }

    @Test
    void resubmitSameBatch_isIdempotent_returnsFirstConclusion() {
        TFlogDeclareBatch first = new TFlogDeclareBatch();
        first.setBatchNo("B3");
        first.setTotalCount(2);
        first.setOkCount(2);
        first.setFailCount(0);
        when(batchMapper.selectOne(any())).thenReturn(first);

        int ok = service.submitBatch("B3", java.util.Arrays.asList(row("A", "1"), row("B", "2")));

        assertEquals(2, ok);
        verify(batchMapper, never()).insert(any());
        verify(rowMapper, never()).insert(org.mockito.ArgumentMatchers.<TFlogDeclareRow>any());
    }

    @Test
    void concurrentResubmit_loserWritesNothing_returnsWinnerConclusion() {
        TFlogDeclareBatch winner = new TFlogDeclareBatch();
        winner.setBatchNo("B4");
        winner.setTotalCount(1);
        winner.setOkCount(1);
        winner.setFailCount(0);
        when(batchMapper.selectOne(any())).thenReturn(null, winner);
        when(batchMapper.insert(any(TFlogDeclareBatch.class)))
                .thenThrow(new DuplicateKeyException("uk_batch_no"));

        int ok = service.submitBatch("B4", java.util.Collections.singletonList(row("A", "1")));

        assertEquals(1, ok, "唯一索引撞车后采用胜出方的头一次结论");
        verify(rowMapper, never()).insert(org.mockito.ArgumentMatchers.<TFlogDeclareRow>any());
    }

    @Test
    void nullSlotsSkipped_notCountedInTotal() {
        int ok = service.submitBatch("B5",
                java.util.Arrays.asList(row("A", "1"), null, row("C", "3")));

        assertEquals(2, ok);
        ArgumentCaptor<TFlogDeclareBatch> headCap = ArgumentCaptor.forClass(TFlogDeclareBatch.class);
        verify(batchMapper).insert(headCap.capture());
        assertEquals(2, headCap.getValue().getTotalCount().intValue(), "空位不计入应收总数");
        verify(rowMapper, times(2)).insert(org.mockito.ArgumentMatchers.<TFlogDeclareRow>any());
    }

    @Test
    void selectBatch_delegatesToHeadLedger() {
        TFlogDeclareBatch head = new TFlogDeclareBatch();
        when(batchMapper.selectOne(any())).thenReturn(head);
        assertSame(head, service.selectBatch(" B1 "), "下游对账只认批次头台账");
    }
}
