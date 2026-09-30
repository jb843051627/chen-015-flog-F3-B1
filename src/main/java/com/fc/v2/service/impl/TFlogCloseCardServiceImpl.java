package com.fc.v2.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fc.v2.mapper.auto.TFlogCloseCardMapper;
import com.fc.v2.model.auto.TFlogCloseCard;
import com.fc.v2.service.ITFlogCloseCardService;

/**
 * 伐区勾销事务卡 Service业务层处理（state-machine 形状：单据流转）
 *
 * <p>段次 0..3（报伐/核株/验更/了断），流转规矩只有一处：</p>
 * <ul>
 *   <li>推进一次恰好走一档，走到末段(3)即落"已了断"；</li>
 *   <li>回退一次恰好退一档（不再一刀切回 0）；</li>
 *   <li>已了断即封档：内容不得再改、卡不得再删/再推进/再回退；</li>
 *   <li>删除一律软删，旧账留痕。</li>
 * </ul>
 * <p>每个动作按单串行 + 事务，并发操作不会走出重复跳档。</p>
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TFlogCloseCardServiceImpl implements ITFlogCloseCardService {

    private static final int MIN_STAGE = 0;
    private static final int MAX_STAGE = 3;
    private static final int STATUS_NOT_STARTED = 0;
    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_TERMINAL = 2;

    /** 按单串行锁条数 */
    private static final int LOCK_STRIPES = 64;
    private final Object[] cardLocks = initLocks();

    private static Object[] initLocks() {
        Object[] locks = new Object[LOCK_STRIPES];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    @javax.annotation.Resource
    private TFlogCloseCardMapper flogCloseCardMapper;

    @Override
    public TFlogCloseCard selectTFlogCloseCardById(Long id) {
        if (id == null) {
            return null;
        }
        return this.flogCloseCardMapper.selectOne(new QueryWrapper<TFlogCloseCard>()
                .eq("id", id)
                .eq("del_flag", 0));
    }

    @Override
    public List<TFlogCloseCard> selectTFlogCloseCardList(QueryWrapper<TFlogCloseCard> queryWrapper) {
        QueryWrapper<TFlogCloseCard> wrapper = queryWrapper == null
                ? new QueryWrapper<TFlogCloseCard>() : queryWrapper;
        // 台账只见未删单据
        wrapper.eq("del_flag", 0);
        return this.flogCloseCardMapper.selectList(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TFlogCloseCard advance(Long id, String remark) {
        if (id == null) {
            return null;
        }
        synchronized (lockFor(id)) {
            TFlogCloseCard r = readActive(id);
            if (r == null || isTerminal(r)) {
                return null;
            }
            int st = r.getStage() == null ? MIN_STAGE : r.getStage().intValue();
            if (st >= MAX_STAGE) {
                return null;
            }
            int next = st + 1;
            r.setStage(Integer.valueOf(next));
            // 到末段落"已了断"，其余段次为"在办"
            r.setStatus(Integer.valueOf(next >= MAX_STAGE ? STATUS_TERMINAL : STATUS_ACTIVE));
            r.setLastAction(remark);
            this.flogCloseCardMapper.updateById(r);
            return r;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TFlogCloseCard rollback(Long id, String remark) {
        if (id == null) {
            return null;
        }
        synchronized (lockFor(id)) {
            TFlogCloseCard r = readActive(id);
            if (r == null || isTerminal(r)) {
                return null;
            }
            int st = r.getStage() == null ? MIN_STAGE : r.getStage().intValue();
            int prev = Math.max(MIN_STAGE, st - 1);
            r.setStage(Integer.valueOf(prev));
            // 退回起段且尚未起办时恢复"未起"，其余为"在办"
            r.setStatus(Integer.valueOf(prev == MIN_STAGE && st == MIN_STAGE
                    ? STATUS_NOT_STARTED : STATUS_ACTIVE));
            r.setLastAction(remark);
            this.flogCloseCardMapper.updateById(r);
            return r;
        }
    }

    @Override
    public boolean updateContent(Long id, String remark) {
        if (id == null) {
            return false;
        }
        TFlogCloseCard r = readActive(id);
        if (r == null || isTerminal(r)) {
            // 已了断封档，内容不得再改
            return false;
        }
        r.setContent(remark);
        return this.flogCloseCardMapper.updateById(r) > 0;
    }

    @Override
    public boolean remove(Long id) {
        if (id == null) {
            return false;
        }
        TFlogCloseCard r = readActive(id);
        if (r == null || isTerminal(r)) {
            // 已了断不得删除；已删的不重复计数
            return false;
        }
        // 软删留痕
        return this.flogCloseCardMapper.update(null, new UpdateWrapper<TFlogCloseCard>()
                .set("del_flag", 1)
                .eq("id", id)
                .eq("del_flag", 0)) > 0;
    }

    private Object lockFor(Long id) {
        return this.cardLocks[(int) ((id.longValue() & Integer.MAX_VALUE) % LOCK_STRIPES)];
    }

    private TFlogCloseCard readActive(Long id) {
        TFlogCloseCard r = this.flogCloseCardMapper.selectById(id);
        if (r == null || (r.getDelFlag() != null && r.getDelFlag().intValue() == 1)) {
            return null;
        }
        return r;
    }

    private static boolean isTerminal(TFlogCloseCard r) {
        return r.getStatus() != null && r.getStatus().intValue() == STATUS_TERMINAL;
    }
}
