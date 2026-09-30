package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fc.v2.mapper.auto.TFlogYieldRuleMapper;
import com.fc.v2.model.auto.TFlogYieldRule;

/**
 * 界限值线口径测试：列表/计数/单查/单码求值/首选求值五处同源。
 */
@ExtendWith(MockitoExtension.class)
class TFlogYieldRuleServiceImplTest {

    @Mock
    private TFlogYieldRuleMapper mapper;
    @InjectMocks
    private TFlogYieldRuleServiceImpl service;

    private final Date at = new Date(1_700_000_000_000L);

    private TFlogYieldRule rule(String code, int priority, int status, Integer del,
                                Date start, Date end) {
        TFlogYieldRule r = new TFlogYieldRule();
        r.setId((long) code.hashCode());
        r.setRuleCode(code);
        r.setPriority(priority);
        r.setStatus(status);
        r.setDelFlag(del == null ? 0 : del);
        r.setEffStart(start);
        r.setEffEnd(end);
        r.setTh1Max(new BigDecimal("40"));
        r.setTh2Max(new BigDecimal("120"));
        r.setTh3Max(new BigDecimal("300"));
        return r;
    }

    private Date daysFromAt(int delta) {
        Calendar c = Calendar.getInstance();
        c.setTime(at);
        c.add(Calendar.DATE, delta);
        return c.getTime();
    }

    @Test
    void 五处口径同源_已让位与窗口外的一律剔除() {
        TFlogYieldRule active = rule("A", 5, 0, 0, null, daysFromAt(1));   // 现行+窗口内
        TFlogYieldRule retired = rule("B", 9, 1, 0, null, null);          // 已让位，优先级再高也剔除
        TFlogYieldRule expired = rule("C", 9, 0, 0, null, daysFromAt(-1));// 窗口已过
        when(mapper.selectList(any())).thenReturn(Arrays.asList(active, retired, expired));

        List<TFlogYieldRule> avail = service.listAvailable(at);
        assertEquals(1, avail.size());
        assertEquals("A", avail.get(0).getRuleCode());
        // 计数必须与列表同源
        assertEquals(1, service.countAvailable(at));
        // 单查也必须过同一个生效谓词
        when(mapper.selectById(active.getId())).thenReturn(active);
        assertTrue(service.usable(active.getId(), at));
        // 窗口外那一刻不可用
        assertFalse(service.usable(active.getId(), daysFromAt(2)));
    }

    @Test
    void 交棒之日不含_当天即失效() {
        TFlogYieldRule r = rule("A", 5, 0, 0, null, at);
        when(mapper.selectList(any())).thenReturn(Arrays.asList(r));
        assertEquals(0, service.evaluate("A", new BigDecimal("10"), at));
        assertEquals(0, service.countAvailable(at));
    }

    @Test
    void 单码求值按优先级之外也认码() {
        TFlogYieldRule a = rule("A", 5, 0, 0, null, null);
        TFlogYieldRule b = rule("B", 9, 0, 0, null, null);
        when(mapper.selectList(any())).thenReturn(Arrays.asList(a, b));
        // 求值指定 A 码，不受 B 优先级更高影响；120 恰好中度线上限 → 2 档
        assertEquals(2, service.evaluate("A", new BigDecimal("120"), at));
        // 首选取优先级最高的 B；超出重度线 → 4 档（旧代码误植 0..100 上限会错返 0）
        assertEquals(4, service.evaluateTop(new BigDecimal("500"), at));
    }

    @Test
    void 负值拒判_缺参返0() {
        assertEquals(0, service.evaluateTop(new BigDecimal("-1"), at));
        assertEquals(0, service.evaluate(null, new BigDecimal("10"), at));
        assertEquals(0, service.countAvailable(null));
    }
}
