package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TFlogYieldRuleMapper;
import com.fc.v2.model.auto.TFlogYieldRule;
import com.fc.v2.service.ITFlogYieldRuleService;

/**
 * 可采强度界限值线 Service业务层处理（rule-eval 形状）
 *
 * <p>"某时刻可用规则"全域只有一个口径，{@link #evaluate}、{@link #evaluateTop}、
 * {@link #listAvailable}、{@link #usable}、{@link #countAvailable} 同源，
 * 不许再各查各的（曾出现列表过滤了时段、单查只看 id，两处对不上的情况）：</p>
 * <ul>
 *   <li>未删除（del_flag=0）且现行（status=0）；</li>
 *   <li>处于生效窗口：eff_start ≤ at &lt; eff_end（交棒之日不含；eff_end 为空视为长期有效）；</li>
 *   <li>优先级降序、并列 ruleCode 降序，顺序确定。</li>
 * </ul>
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TFlogYieldRuleServiceImpl implements ITFlogYieldRuleService {

    /** 线的情形：现行 */
    private static final int STATUS_ACTIVE = 0;

    /** 优先级降序；并列按 ruleCode 降序（确定性） */
    private static final Comparator<TFlogYieldRule> PRIORITY_THEN_CODE_DESC = new Comparator<TFlogYieldRule>() {
        @Override
        public int compare(TFlogYieldRule a, TFlogYieldRule b) {
            int pa = a.getPriority() == null ? 0 : a.getPriority();
            int pb = b.getPriority() == null ? 0 : b.getPriority();
            if (pa != pb) {
                return pb - pa;
            }
            String ca = a.getRuleCode() == null ? "" : a.getRuleCode();
            String cb = b.getRuleCode() == null ? "" : b.getRuleCode();
            return cb.compareTo(ca);
        }
    };

    @javax.annotation.Resource
    private TFlogYieldRuleMapper flogYieldRuleMapper;

    @Override
    public TFlogYieldRule selectTFlogYieldRuleById(Long id) {
        return this.flogYieldRuleMapper.selectById(id);
    }

    @Override
    public List<TFlogYieldRule> listAvailable(Date at) {
        if (at == null) {
            return new java.util.ArrayList<TFlogYieldRule>();
        }
        // 库内粗筛：未删 + 现行；生效窗口在内存用同一谓词 isEffective 精判，
        // 保证列表/计数/单查/求值看到的"可用"完全同义。
        List<TFlogYieldRule> all = this.flogYieldRuleMapper.selectList(
                new QueryWrapper<TFlogYieldRule>()
                        .eq("del_flag", 0)
                        .eq("status", STATUS_ACTIVE));
        List<TFlogYieldRule> avail = new java.util.ArrayList<TFlogYieldRule>();
        for (TFlogYieldRule r : all) {
            if (isUsable(r, at)) {
                avail.add(r);
            }
        }
        avail.sort(PRIORITY_THEN_CODE_DESC);
        return avail;
    }

    @Override
    public int evaluate(String ruleCode, BigDecimal input, Date at) {
        if (ruleCode == null || ruleCode.trim().isEmpty() || input == null || at == null) {
            return 0;
        }
        if (input.compareTo(BigDecimal.ZERO) < 0) {
            return 0;
        }
        for (TFlogYieldRule r : listAvailable(at)) {
            if (ruleCode.equals(r.getRuleCode())) {
                return levelOf(r, input);
            }
        }
        // at 时刻无该码的生效版本
        return 0;
    }

    @Override
    public int evaluateTop(BigDecimal input, Date at) {
        if (input == null || at == null || input.compareTo(BigDecimal.ZERO) < 0) {
            return 0;
        }
        List<TFlogYieldRule> avail = listAvailable(at);
        if (avail.isEmpty()) {
            return 0;
        }
        return levelOf(avail.get(0), input);
    }

    @Override
    public boolean usable(Long id, Date at) {
        if (id == null || at == null) {
            return false;
        }
        TFlogYieldRule r = this.flogYieldRuleMapper.selectById(id);
        // 与列表/计数/求值同一谓词：未删 + 现行 + 在生效窗口内
        return r != null && isUsable(r, at);
    }

    /**
     * "某时刻可用"唯一谓词：未删（del_flag=0）+ 现行（status=0）
     * + 处于生效窗口 {@link #isEffective}。列表/单查/计数/求值共用。
     */
    private static boolean isUsable(TFlogYieldRule r, Date at) {
        if (r.getDelFlag() != null && r.getDelFlag().intValue() != 0) {
            return false;
        }
        if (r.getStatus() == null || r.getStatus().intValue() != STATUS_ACTIVE) {
            return false;
        }
        return isEffective(r, at);
    }

    @Override
    public int countAvailable(Date at) {
        // 条数与列表同源，不再另写一个漏过滤时段的查询
        return listAvailable(at).size();
    }

    /**
     * 生效窗口唯一谓词：启用之日 ≤ at &lt; 交棒之日（不含）。
     * eff_start 为空视为早已启用；eff_end 为空视为长期有效。
     */
    private static boolean isEffective(TFlogYieldRule r, Date at) {
        Date start = r.getEffStart();
        if (start != null && at.before(start)) {
            return false;
        }
        Date end = r.getEffEnd();
        return end == null || at.before(end);
    }

    /** 分档：等于上限取高一档（与在册底档定档口径一致） */
    private int levelOf(TFlogYieldRule rule, BigDecimal input) {
        if (rule.getTh1Max() != null && input.compareTo(rule.getTh1Max()) <= 0) {
            return 1;
        }
        if (rule.getTh2Max() != null && input.compareTo(rule.getTh2Max()) <= 0) {
            return 2;
        }
        if (rule.getTh3Max() != null && input.compareTo(rule.getTh3Max()) <= 0) {
            return 3;
        }
        return 4;
    }
}
