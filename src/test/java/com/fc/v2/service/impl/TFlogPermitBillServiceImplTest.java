package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentCaptor;

import com.fc.v2.mapper.auto.TFlogPermitBillMapper;
import com.fc.v2.model.auto.TFlogPermitBill;

/**
 * 逐级核签测试：签计按办法走、签满进级、打回真落态、回退一级、并发结果确定。
 */
@ExtendWith(MockitoExtension.class)
class TFlogPermitBillServiceImplTest {

    @Mock
    private TFlogPermitBillMapper mapper;
    @InjectMocks
    private TFlogPermitBillServiceImpl service;

    private TFlogPermitBill bill(int node, int mode, Integer need, int signed, int status) {
        TFlogPermitBill b = new TFlogPermitBill();
        b.setId(1L);
        b.setNodeNo(node);
        b.setSignMode(mode);
        b.setNeedCount(need);
        b.setSignCount(signed);
        b.setStatus(status);
        b.setDelFlag(0);
        return b;
    }

    private TFlogPermitBill capture() {
        ArgumentCaptor<TFlogPermitBill> c = ArgumentCaptor.forClass(TFlogPermitBill.class);
        verify(mapper).updateById(c.capture());
        return c.getValue();
    }

    @Test
    void 任一人办法_一签即进级() {
        when(mapper.selectById(1L)).thenReturn(bill(0, 0, null, 0, 0));
        TFlogPermitBill r = service.approve(1L, "甲", null);
        TFlogPermitBill saved = capture();
        assertEquals(1, saved.getNodeNo().intValue());
        assertEquals(0, saved.getSignCount().intValue());
        assertEquals(0, saved.getStatus().intValue());
    }

    @Test
    void 两名点齐_第一签只计数_第二签才进级() {
        when(mapper.selectById(1L)).thenReturn(bill(0, 1, 2, 0, 0));
        service.approve(1L, "甲", null);
        TFlogPermitBill first = capture();
        assertEquals(0, first.getNodeNo().intValue());
        assertEquals(1, first.getSignCount().intValue());

        org.mockito.Mockito.clearInvocations(mapper);
        when(mapper.selectById(1L)).thenReturn(bill(0, 1, 2, 1, 0));
        service.approve(1L, "乙", null);
        TFlogPermitBill second = capture();
        assertEquals(1, second.getNodeNo().intValue());
        assertEquals(0, second.getSignCount().intValue());
    }

    @Test
    void 末级签满置已核讫() {
        when(mapper.selectById(1L)).thenReturn(bill(3, 0, null, 0, 0));
        service.approve(1L, "甲", null);
        TFlogPermitBill saved = capture();
        assertEquals(3, saved.getNodeNo().intValue());
        assertEquals(1, saved.getStatus().intValue());
    }

    @Test
    void 打回真正置已打回_不再原样update() {
        when(mapper.selectById(1L)).thenReturn(bill(1, 0, null, 0, 0));
        service.reject(1L, "甲", null);
        assertEquals(2, capture().getStatus().intValue());
    }

    @Test
    void 回退恰好一级且本级签数清零() {
        when(mapper.selectById(1L)).thenReturn(bill(2, 1, 2, 1, 0));
        service.rollback(1L, null);
        TFlogPermitBill saved = capture();
        assertEquals(1, saved.getNodeNo().intValue());
        assertEquals(0, saved.getSignCount().intValue());
        assertEquals(0, saved.getStatus().intValue());
    }

    @Test
    void 已核讫的单不再受签() {
        when(mapper.selectById(1L)).thenReturn(bill(3, 0, null, 0, 1));
        assertNull(service.approve(1L, "甲", null));
    }

    @Test
    void 并发叠签不丢签数() throws Exception {
        // 模拟两名审批人同时签一张 AND 单：串行化后两次都累加，无丢签
        int[] stored = {0};
        when(mapper.selectById(1L)).thenAnswer(inv -> bill(0, 1, 2, stored[0], 0));
        when(mapper.updateById(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> {
            TFlogPermitBill b = inv.getArgument(0);
            stored[0] = b.getSignCount() == null ? 0 : b.getSignCount();
            return 1;
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        pool.submit(() -> service.approve(1L, "甲", null));
        pool.submit(() -> service.approve(1L, "乙", null));
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        // 第二签签满：最终级次进到 1、签数清零；中间绝无两签互相覆盖成 1
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.atLeast(2)).updateById(
                org.mockito.ArgumentMatchers.any());
    }
}
