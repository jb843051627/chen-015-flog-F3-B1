package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fc.v2.mapper.auto.TFlogDeclareBatchMapper;
import com.fc.v2.mapper.auto.TFlogDeclareRowMapper;
import com.fc.v2.model.auto.TFlogDeclareBatch;
import com.fc.v2.model.auto.TFlogDeclareRow;

/**
 * 整批核收规矩测试：不封顶、逐行落库、批内去重、重发幂等。
 */
@ExtendWith(MockitoExtension.class)
class TFlogDeclareRowServiceImplTest {

    @Mock
    private TFlogDeclareRowMapper rowMapper;
    @Mock
    private TFlogDeclareBatchMapper batchMapper;
    @InjectMocks
    private TFlogDeclareRowServiceImpl service;

    private TFlogDeclareRow row(String code, String qty) {
        TFlogDeclareRow r = new TFlogDeclareRow();
        r.setItemCode(code);
        r.setQty(new BigDecimal(qty));
        return r;
    }

    @BeforeEach
    void setUp() {
        // 默认批次台账不存在（头一次提交）；幂等/空批用例用不到这些桩，开 lenient 免报错
        org.mockito.Mockito.lenient().when(batchMapper.selectOne(any())).thenReturn(null);
        org.mockito.Mockito.lenient().when(batchMapper.insert(any(TFlogDeclareBatch.class))).thenReturn(1);
        org.mockito.Mockito.lenient().when(rowMapper.insert(any(TFlogDeclareRow.class))).thenReturn(1);
    }

    @Test
    void 单批行数不封顶_来多少收多少() {
        List<TFlogDeclareRow> rows = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            rows.add(row("CODE-" + i, "10"));
        }
        int ok = service.submitBatch("B1", rows);
        assertEquals(5000, ok);
        verify(rowMapper, times(5000)).insert(any(TFlogDeclareRow.class));
    }

    @Test
    void 一行个别字错_其余行照常入库_不整批退回() {
        List<TFlogDeclareRow> rows = new ArrayList<>();
        rows.add(row("CODE-1", "10"));
        TFlogDeclareRow bad = row("   ", "10"); // 明细码空白
        rows.add(bad);
        rows.add(row("CODE-2", "10"));

        int ok = service.submitBatch("B2", rows);
        assertEquals(2, ok);
        // 成功两行 + 失败一行，整批三行全部有下落
        verify(rowMapper, times(3)).insert(any(TFlogDeclareRow.class));
        assertEquals(2, bad.getStatus().intValue());
        assertTrue(bad.getRemark().contains("明细码为空"));
    }

    @Test
    void 批内同码去重_只收头一行() {
        List<TFlogDeclareRow> rows = new ArrayList<>();
        rows.add(row("DUP", "10"));
        rows.add(row("DUP", "20"));

        int ok = service.submitBatch("B3", rows);
        assertEquals(1, ok);
        verify(rowMapper, times(2)).insert(any(TFlogDeclareRow.class));
        assertEquals(2, rows.get(1).getStatus().intValue());
        assertTrue(rows.get(1).getRemark().contains("重复"));
    }

    @Test
    void 同一批重发_幂等返回_不生第二份() {
        TFlogDeclareBatch ledger = new TFlogDeclareBatch();
        ledger.setBatchNo("B4");
        ledger.setOkRows(7);
        when(batchMapper.selectOne(any())).thenReturn(ledger);

        int ok = service.submitBatch("B4", java.util.Collections.singletonList(row("X", "1")));
        assertEquals(7, ok);
        verify(batchMapper, never()).insert(any(TFlogDeclareBatch.class));
        verify(rowMapper, never()).insert(any(TFlogDeclareRow.class));
    }

    @Test
    void 空批返回0() {
        assertEquals(0, service.submitBatch("B5", new ArrayList<>()));
        assertEquals(0, service.submitBatch(null, new ArrayList<>()));
        verify(batchMapper, never()).insert(any(TFlogDeclareBatch.class));
    }
}
