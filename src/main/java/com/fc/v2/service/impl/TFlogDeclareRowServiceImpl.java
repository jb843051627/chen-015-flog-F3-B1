package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TFlogDeclareBatchMapper;
import com.fc.v2.mapper.auto.TFlogDeclareRowMapper;
import com.fc.v2.model.auto.TFlogDeclareBatch;
import com.fc.v2.model.auto.TFlogDeclareRow;
import com.fc.v2.service.ITFlogDeclareRowService;

/**
 * 伐区申报清册核收行 Service业务层处理（batch-process 形状：整批提交）
 *
 * <p>核收规矩（全批统一，不得各说各话）：</p>
 * <ul>
 *   <li>单批行数不封顶，来多少收多少；</li>
 *   <li>逐行核收：合法行入库、非法行记失败明细，单行个别字错不再整批退回；</li>
 *   <li>批内按明细码去重：同一码只收头一行，其余记失败原因，不重复落库；</li>
 *   <li>同一批次号重发直接幂等返回，不重生第二份（批次台账 + 唯一索引兜底并发）；</li>
 *   <li>批次台账永久落库，成功/失败/总数都有旧账可查。</li>
 * </ul>
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TFlogDeclareRowServiceImpl implements ITFlogDeclareRowService {

    private static final int STATUS_OK = 1;
    private static final int STATUS_FAIL = 2;
    /** 台账情形：已结清 */
    private static final int LEDGER_DONE = 1;
    /** 单行明细码长度上限 */
    private static final int ITEM_CODE_MAX = 64;
    /** 单行数量上限（超出视为录入错误，防止脏数据拉爆统计） */
    private static final BigDecimal QTY_MAX = new BigDecimal("999999");

    /** 同批次号串行锁的条数（按批次号哈希取条，够用且不随批次数涨内存） */
    private static final int LOCK_STRIPES = 64;
    private final Object[] batchLocks = initLocks();

    private static Object[] initLocks() {
        Object[] locks = new Object[LOCK_STRIPES];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    @javax.annotation.Resource
    private TFlogDeclareRowMapper flogDeclareRowMapper;

    @javax.annotation.Resource
    private TFlogDeclareBatchMapper flogDeclareBatchMapper;

    @Override
    public TFlogDeclareRow selectTFlogDeclareRowById(Long id) {
        return this.flogDeclareRowMapper.selectById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int submitBatch(String batchNo, List<TFlogDeclareRow> rows) {
        // 空批兜底：批次号或明细缺失即安全返回 0。
        if (isBlank(batchNo) || rows == null || rows.isEmpty()) {
            return 0;
        }
        // 同一批的重发/并发提交在同一把锁上排队；规矩一次只许一处开口。
        synchronized (lockFor(batchNo)) {
            TFlogDeclareBatch existed = this.flogDeclareBatchMapper.selectOne(
                    new QueryWrapper<TFlogDeclareBatch>()
                            .eq("batch_no", batchNo)
                            .eq("del_flag", 0));
            if (existed != null) {
                // 同一批次重发：幂等返回头一次的成功行数，绝不再生一份。
                return existed.getOkRows() == null ? 0 : existed.getOkRows().intValue();
            }

            // 先在内存里把整批核收完：行号归一、逐行校验、批内同码去重，都不落库。
            List<TFlogDeclareRow> accepted = new ArrayList<TFlogDeclareRow>();
            List<TFlogDeclareRow> rejected = new ArrayList<TFlogDeclareRow>();
            Set<Integer> usedRowNo = new HashSet<Integer>();
            // 明细码（去空白）-> 头一次出现的行次，批内去重只认头一行
            Map<String, Integer> seenItemRow = new HashMap<String, Integer>();
            int total = 0;
            int seq = 0;
            for (TFlogDeclareRow r : rows) {
                if (r == null) {
                    continue;
                }
                total++;
                seq++;
                int rowNo = normalizeRowNo(r, usedRowNo, seq);
                String invalid = invalidReason(r);
                if (invalid == null) {
                    Integer firstRow = seenItemRow.get(itemKey(r));
                    if (firstRow != null) {
                        invalid = "明细码与第 " + firstRow + " 行重复";
                    } else {
                        seenItemRow.put(itemKey(r), Integer.valueOf(rowNo));
                    }
                }
                if (invalid != null) {
                    rejected.add(stamp(r, batchNo, rowNo, STATUS_FAIL, invalid));
                } else {
                    accepted.add(stamp(r, batchNo, rowNo, STATUS_OK, null));
                }
            }

            // 先立批次台账（batch_no 唯一索引兜底多机并发）：台账立住才允许落明细，
            // 抢建台账的并发重发在此处撞唯一键，按"头一次已收"幂等返回。
            TFlogDeclareBatch ledger = new TFlogDeclareBatch();
            ledger.setBatchNo(batchNo);
            ledger.setTotalRows(Integer.valueOf(total));
            ledger.setOkRows(Integer.valueOf(accepted.size()));
            ledger.setFailRows(Integer.valueOf(rejected.size()));
            ledger.setStatus(Integer.valueOf(LEDGER_DONE));
            ledger.setDelFlag(0);
            ledger.setRemark("提交完成：成功 " + accepted.size() + " 行，失败 " + rejected.size() + " 行");
            try {
                this.flogDeclareBatchMapper.insert(ledger);
            } catch (DuplicateKeyException race) {
                TFlogDeclareBatch winner = this.flogDeclareBatchMapper.selectOne(
                        new QueryWrapper<TFlogDeclareBatch>()
                                .eq("batch_no", batchNo)
                                .eq("del_flag", 0));
                return winner != null && winner.getOkRows() != null
                        ? winner.getOkRows().intValue() : 0;
            }

            // 整批一个事务：台账与明细同生共死，账上的数同明细必然凑得拢。
            for (TFlogDeclareRow r : accepted) {
                this.flogDeclareRowMapper.insert(r);
            }
            for (TFlogDeclareRow r : rejected) {
                this.flogDeclareRowMapper.insert(r);
            }
            return accepted.size();
        }
    }

    @Override
    public List<TFlogDeclareRow> listErrors(String batchNo) {
        if (isBlank(batchNo)) {
            return new ArrayList<TFlogDeclareRow>();
        }
        List<TFlogDeclareRow> errs = this.flogDeclareRowMapper.selectList(new QueryWrapper<TFlogDeclareRow>()
                .eq("batch_no", batchNo)
                .eq("status", STATUS_FAIL)
                .eq("del_flag", 0));
        if (errs == null || errs.isEmpty()) {
            return new ArrayList<TFlogDeclareRow>();
        }
        errs.sort(new java.util.Comparator<TFlogDeclareRow>() {
            @Override
            public int compare(TFlogDeclareRow a, TFlogDeclareRow b) {
                int ra = a.getRowNo() == null ? 0 : a.getRowNo().intValue();
                int rb = b.getRowNo() == null ? 0 : b.getRowNo().intValue();
                return ra - rb;
            }
        });
        return errs;
    }

    /** 批次号哈希取条锁 */
    private Object lockFor(String batchNo) {
        return this.batchLocks[(batchNo.hashCode() & Integer.MAX_VALUE) % LOCK_STRIPES];
    }

    /** 明细码去重键：去首尾空白，码不做大小写归一（业务码原样区分） */
    private static String itemKey(TFlogDeclareRow r) {
        return r.getItemCode().trim();
    }

    /** 空串判定：批次号/明细码的纯空白串按"缺失"处理 */
    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** 单行校验：返回 null 表示合法；否则返回失败原因（原因写入失败明细备注，便于运维定位） */
    private String invalidReason(TFlogDeclareRow r) {
        if (isBlank(r.getItemCode())) {
            return "明细码为空";
        }
        if (r.getItemCode().trim().length() > ITEM_CODE_MAX) {
            return "明细码超过 " + ITEM_CODE_MAX + " 个字符";
        }
        if (r.getQty() == null) {
            return "数量缺失";
        }
        if (r.getQty().compareTo(BigDecimal.ZERO) <= 0) {
            return "数量必须大于 0";
        }
        if (r.getQty().compareTo(QTY_MAX) > 0) {
            return "数量超过单行上限 " + QTY_MAX.toPlainString();
        }
        return null;
    }

    /** 原始行号归一：优先保留调用方给的 Excel 行号，缺失/冲突时才补一个未占用序号 */
    private static int normalizeRowNo(TFlogDeclareRow r, Set<Integer> used, int fallback) {
        Integer given = r.getRowNo();
        if (given != null && given.intValue() > 0 && used.add(given)) {
            return given.intValue();
        }
        int candidate = fallback > 0 ? fallback : 1;
        while (!used.add(Integer.valueOf(candidate))) {
            candidate++;
        }
        return candidate;
    }

    /** 落库前盖章：批次/行次/情形/原因一次定死，软删标记补 0，时间由服务端生成 */
    private static TFlogDeclareRow stamp(TFlogDeclareRow r, String batchNo, int rowNo, int status, String reason) {
        r.setBatchNo(batchNo);
        r.setRowNo(Integer.valueOf(rowNo));
        r.setStatus(Integer.valueOf(status));
        r.setRemark(reason);
        if (r.getDelFlag() == null) {
            r.setDelFlag(0);
        }
        r.setUpdateTime(new Date());
        return r;
    }
}
