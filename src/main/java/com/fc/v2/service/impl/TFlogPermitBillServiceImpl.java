package com.fc.v2.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fc.v2.mapper.auto.TFlogPermitBillMapper;
import com.fc.v2.model.auto.TFlogPermitBill;
import com.fc.v2.service.ITFlogPermitBillService;

/**
 * 采伐许可证逐级核签单 Service业务层处理（approval-chain 形状：多阶段签批）
 *
 * <p>级次 0..3 共四级。本级签批规矩只有一处口径：</p>
 * <ul>
 *   <li>任一人（MODE_OR）：1 人签即满；</li>
 *   <li>两名点齐（MODE_AND）：need_count 人签满（缺省 2）；</li>
 *   <li>签满才进下一级并清零本级已签数；末级签满置"已核讫"。</li>
 * </ul>
 * <p>签批、打回、回退共用同一级次/情形状态机，且每单串行 + 事务：
 * 并发叠签不再丢签数，回退后重签与串行结果一致。</p>
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TFlogPermitBillServiceImpl implements ITFlogPermitBillService {

    /** 末级序号（级次 0..3 共四级） */
    private static final int MAX_NODE = 3;
    private static final int MODE_OR = 0;
    private static final int MODE_AND = 1;
    private static final int STATUS_RUNNING = 0;
    private static final int STATUS_PASS = 1;
    private static final int STATUS_VETO = 2;

    /** 按单串行锁条数 */
    private static final int LOCK_STRIPES = 64;
    private final Object[] billLocks = initLocks();

    private static Object[] initLocks() {
        Object[] locks = new Object[LOCK_STRIPES];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    @javax.annotation.Resource
    private TFlogPermitBillMapper flogPermitBillMapper;

    @Override
    public TFlogPermitBill selectTFlogPermitBillById(Long id) {
        return this.flogPermitBillMapper.selectById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TFlogPermitBill approve(Long id, String approver, String comment) {
        if (id == null || approver == null || approver.trim().isEmpty()) {
            return null;
        }
        synchronized (lockFor(id)) {
            TFlogPermitBill r = readActive(id);
            if (r == null) {
                return null;
            }
            // 已核讫/已打回的单子不再受签
            if (!isRunning(r)) {
                return null;
            }
            int signed = r.getSignCount() == null ? 0 : r.getSignCount().intValue();
            signed++;
            int required = requiredSigners(r);
            if (signed < required) {
                // 本级尚未签齐：只累加已签数，级次不动
                r.setSignCount(Integer.valueOf(signed));
                r.setStatus(Integer.valueOf(STATUS_RUNNING));
                this.flogPermitBillMapper.updateById(r);
                return r;
            }
            // 本级签齐：进一级，已签数清零；末级签齐则核讫
            int node = r.getNodeNo() == null ? 0 : r.getNodeNo().intValue();
            r.setSignCount(0);
            if (node >= MAX_NODE) {
                r.setNodeNo(Integer.valueOf(MAX_NODE));
                r.setStatus(Integer.valueOf(STATUS_PASS));
            } else {
                r.setNodeNo(Integer.valueOf(node + 1));
                r.setStatus(Integer.valueOf(STATUS_RUNNING));
            }
            this.flogPermitBillMapper.updateById(r);
            return r;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TFlogPermitBill reject(Long id, String approver, String comment) {
        if (id == null || approver == null || approver.trim().isEmpty()) {
            return null;
        }
        synchronized (lockFor(id)) {
            TFlogPermitBill r = readActive(id);
            if (r == null || !isRunning(r)) {
                return null;
            }
            // 打回即终态，级次与已签数原样留痕，不得只 update 一遍却不改情形
            r.setStatus(Integer.valueOf(STATUS_VETO));
            this.flogPermitBillMapper.updateById(r);
            return r;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TFlogPermitBill rollback(Long id, String comment) {
        if (id == null) {
            return null;
        }
        synchronized (lockFor(id)) {
            TFlogPermitBill r = readActive(id);
            if (r == null || !isRunning(r)) {
                return null;
            }
            // 退回上一环节：恰好退一级（不是跳级、也不是一刀切回 0），本级已签清零重来
            int node = r.getNodeNo() == null ? 0 : r.getNodeNo().intValue();
            r.setNodeNo(Integer.valueOf(Math.max(0, node - 1)));
            r.setSignCount(0);
            r.setStatus(Integer.valueOf(STATUS_RUNNING));
            this.flogPermitBillMapper.updateById(r);
            return r;
        }
    }

    private Object lockFor(Long id) {
        return this.billLocks[(int) ((id.longValue() & Integer.MAX_VALUE) % LOCK_STRIPES)];
    }

    private TFlogPermitBill readActive(Long id) {
        TFlogPermitBill r = this.flogPermitBillMapper.selectById(id);
        if (r == null || (r.getDelFlag() != null && r.getDelFlag().intValue() == 1)) {
            return null;
        }
        return r;
    }

    private static boolean isRunning(TFlogPermitBill r) {
        return r.getStatus() == null || r.getStatus().intValue() == STATUS_RUNNING;
    }

    /** 本级应签人数：任一人=1；两名点齐=need_count（缺省 2） */
    private static int requiredSigners(TFlogPermitBill r) {
        int mode = r.getSignMode() == null ? MODE_OR : r.getSignMode().intValue();
        if (mode == MODE_AND) {
            return r.getNeedCount() != null && r.getNeedCount().intValue() > 0
                    ? r.getNeedCount().intValue() : 2;
        }
        return 1;
    }
}
