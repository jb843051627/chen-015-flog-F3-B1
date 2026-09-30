package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fc.v2.mapper.auto.TFlogQuotaTaskMapper;
import com.fc.v2.model.auto.TFlogQuotaTask;

/**
 * 限额事项到期执行测试：窗口谓词单一出处、空集不崩、并发抢占不重复计数、单条失败跳过。
 */
@ExtendWith(MockitoExtension.class)
class TFlogQuotaTaskServiceImplTest {

    @Mock
    private TFlogQuotaTaskMapper mapper;
    @InjectMocks
    private TFlogQuotaTaskServiceImpl service;

    private final Date at = new Date(1_700_000_000_000L);

    private Date daysFromAt(int delta) {
        Calendar c = Calendar.getInstance();
        c.setTime(at);
        c.add(Calendar.DATE, delta);
        return c.getTime();
    }

    private TFlogQuotaTask task(long id, Date dueAt, String leadDays, int status, int del) {
        TFlogQuotaTask t = new TFlogQuotaTask();
        t.setId(id);
        t.setDueAt(dueAt);
        t.setAmount(leadDays == null ? null : new BigDecimal(leadDays));
        t.setStatus(status);
        t.setDelFlag(del);
        return t;
    }

    @Test
    void 空集不崩() {
        when(mapper.selectList(any())).thenReturn(Collections.emptyList());
        assertEquals(0, service.runOnce(at));
        verify(mapper, never()).update(any(), any());
    }

    @Test
    void 到期谓词_开口前两日即该处理() {
        // 截止明天，提前 2 自然日开口 → 今天已在窗口内
        TFlogQuotaTask due = task(1, daysFromAt(1), "2", 0, 0);
        // 截止三十天后、无提前量 → 不到期
        TFlogQuotaTask future = task(2, daysFromAt(30), "0", 0, 0);
        // 已落地 / 已删除 → 即使到期也剔除
        TFlogQuotaTask done = task(3, daysFromAt(1), "2", 1, 0);
        TFlogQuotaTask deleted = task(4, daysFromAt(1), "2", 0, 1);
        when(mapper.selectList(any())).thenReturn(Arrays.asList(due, future, done, deleted));

        assertEquals(1, service.listDue(at).size());
        when(mapper.update(isNull(), any())).thenReturn(1);
        assertEquals(1, service.runOnce(at));
        // 只有一条进入条件抢占
        verify(mapper, times(1)).update(isNull(), any());
    }

    @Test
    void 并发抢占只得手一方() {
        TFlogQuotaTask due = task(1, daysFromAt(1), "2", 0, 0);
        when(mapper.selectList(any())).thenReturn(Collections.singletonList(due));
        // 另一方已先落地：条件更新 status=0 命中 0 行
        when(mapper.update(isNull(), any())).thenReturn(0);
        assertEquals(0, service.runOnce(at));
    }

    @Test
    void amount为空不NPE_到期当日开口() {
        TFlogQuotaTask t = task(1, at, null, 0, 0);
        when(mapper.selectList(any())).thenReturn(Collections.singletonList(t));
        when(mapper.update(isNull(), any())).thenReturn(1);
        assertEquals(1, service.runOnce(at));
    }

    @Test
    void 单条失败跳过继续() {
        TFlogQuotaTask a = task(1, daysFromAt(1), "2", 0, 0);
        TFlogQuotaTask b = task(2, daysFromAt(1), "2", 0, 0);
        when(mapper.selectList(any())).thenReturn(Arrays.asList(a, b));
        when(mapper.update(isNull(), any()))
                .thenThrow(new RuntimeException("库抖了一下"))
                .thenReturn(1);
        assertEquals(1, service.runOnce(at));
    }
}
