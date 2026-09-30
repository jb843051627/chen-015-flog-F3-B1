package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fc.v2.mapper.auto.TFlogQuotaTaskMapper;
import com.fc.v2.model.auto.TFlogQuotaTask;
import com.fc.v2.service.ITFlogQuotaTaskService;

/**
 * 年采伐限额年度执行额度下达与结余收回事项 Service业务层处理（scheduling-job 形状：周期执行）
 *
 * <p>"此刻该处理哪些条目"全域只有一个口径，{@link #listDue} 与 {@link #runOnce}
 * 共用 {@link #isDue}，不许一处看 due_at、另一处只看内存标记（并发叠上回滚时
 * 两处曾得出不一样的数）：</p>
 * <ul>
 *   <li>未删除（del_flag=0）且尚未开口（status=0）；</li>
 *   <li>已到执行窗口开口：at ≥ due_at − amount 个自然日（amount 为开口前
 *       预先提醒的自然日数；为空表示到期当日才开口，负数按 0 算）；</li>
 *   <li>逾期未办的仍算到期，必须能补处理。</li>
 * </ul>
 * <p>落地用条件更新 status 0→1 抢占，多实例并发/事务回滚都不会重复计数。</p>
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TFlogQuotaTaskServiceImpl implements ITFlogQuotaTaskService {

    private static final int STATUS_WAIT = 0;
    private static final int STATUS_DONE = 1;

    @javax.annotation.Resource
    private TFlogQuotaTaskMapper flogQuotaTaskMapper;

    @Override
    public TFlogQuotaTask selectTFlogQuotaTaskById(Long id) {
        return this.flogQuotaTaskMapper.selectById(id);
    }

    @Override
    public List<TFlogQuotaTask> listDue(Date at) {
        List<TFlogQuotaTask> all = this.flogQuotaTaskMapper.selectList(new QueryWrapper<TFlogQuotaTask>());
        java.util.List<TFlogQuotaTask> due = new java.util.ArrayList<TFlogQuotaTask>();
        for (TFlogQuotaTask r : all) {
            if (isDue(r, at)) {
                due.add(r);
            }
        }
        return due;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int runOnce(Date at) {
        if (at == null) {
            return 0;
        }
        List<TFlogQuotaTask> due = listDue(at);
        int ok = 0;
        for (TFlogQuotaTask r : due) {
            try {
                // 条件抢占：仍处于未开口的才落地。并发/回滚下至多一方得手，
                // 成功条数与 listDue 的口径天然对得拢。
                int hit = this.flogQuotaTaskMapper.update(null, new UpdateWrapper<TFlogQuotaTask>()
                        .set("status", STATUS_DONE)
                        .set("update_time", new Date())
                        .eq("id", r.getId())
                        .eq("status", STATUS_WAIT)
                        .eq("del_flag", 0));
                if (hit > 0) {
                    ok++;
                }
            } catch (RuntimeException singleFail) {
                // 单条失败跳过继续，不连累同批其余条目
            }
        }
        return ok;
    }

    /** 到期谓词（listDue / runOnce 唯一出处） */
    private static boolean isDue(TFlogQuotaTask r, Date at) {
        if (at == null || r.getDueAt() == null) {
            return false;
        }
        if (r.getDelFlag() != null && r.getDelFlag().intValue() == 1) {
            return false;
        }
        if (r.getStatus() != null && r.getStatus().intValue() != STATUS_WAIT) {
            return false;
        }
        return !at.before(windowOpensAt(r));
    }

    /** 窗口开口时刻 = 动手最后一刻往前拨 amount 个自然日 */
    private static Date windowOpensAt(TFlogQuotaTask r) {
        long days = 0L;
        BigDecimal amount = r.getAmount();
        if (amount != null && amount.signum() > 0) {
            days = amount.longValue();
        }
        if (days == 0L) {
            return r.getDueAt();
        }
        Calendar cal = Calendar.getInstance();
        cal.setTime(r.getDueAt());
        cal.add(Calendar.DATE, (int) -Math.min(days, (long) Integer.MAX_VALUE));
        return cal.getTime();
    }
}
