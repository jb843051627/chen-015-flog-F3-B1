package com.fc.v2.service.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
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
 * <p>口径统一说明（汇总与明细同源）：批次头 {@code t_flog_declare_batch} 是唯一台账，
 * 核收行是明细，二者在同一事务内写入；成功行数、挂起行数只在 {@link #submitBatch}
 * 里算一遍落进批次头，返回值、批次头、明细行数三处恒等，下游一律以批次头为准，
 * 不许各自重算。批次头对 batch_no 有唯一索引，配合进程内分段锁兜住并发重发。</p>
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TFlogDeclareRowServiceImpl implements ITFlogDeclareRowService {

    private static final int STATUS_OK = 1;
    private static final int STATUS_FAIL = 2;
    private static final int BATCH_DONE = 1;
    /** 单批行数上限：超出返回 -1 且整批不入库 */
    public static final int BATCH_ROW_MAX = 5000;
    /** 单行明细码长度上限 */
    private static final int ITEM_CODE_MAX = 64;
    /** 单行数量上限（超出视为录入错误，防止脏数据拉爆统计） */
    private static final java.math.BigDecimal QTY_MAX = new java.math.BigDecimal("999999");

    /** 批次号分段锁：同一批次串行提交，挡住单机并发重发；不同批次互不阻塞 */
    private static final int LOCK_STRIPES = 256;
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
        // 空批兜底：批次号或明细缺失即安全返回 0，不留批次头。
        if (isBlank(batchNo) || rows == null || rows.isEmpty()) {
            return 0;
        }
        // 单批封顶：超限整批不收，避免超大包拖垮落库与统计。
        if (rows.size() > BATCH_ROW_MAX) {
            return -1;
        }

        String batchKey = batchNo.trim();
        synchronized (lockOf(batchKey)) {
            TFlogDeclareBatch exist = selectBatch(batchKey);
            if (exist != null) {
                // 重发幂等：同一批次已核收，原样回头一次结论，不再落一份明细。
                return exist.getOkCount() == null ? 0 : exist.getOkCount().intValue();
            }

            // 先在内存里把整批逐行定完——计数只产生这一遍，批次头/明细共用同一结果。
            Set<Integer> usedRowNo = new HashSet<Integer>();
            Set<String> seenItemCodes = new HashSet<String>();
            List<TFlogDeclareRow> accepted = new ArrayList<TFlogDeclareRow>();
            List<HangRow> hanged = new ArrayList<HangRow>();
            int seq = 0;
            for (TFlogDeclareRow r : rows) {
                if (r == null) {
                    // 空位不算一行应收明细，跳过且不计入总数。
                    continue;
                }
                seq++;
                int rowNo = normalizeRowNo(r, usedRowNo, seq);
                String invalid = invalidReason(r);
                if (invalid == null) {
                    String code = r.getItemCode().trim();
                    r.setItemCode(code);
                    // 批内去重：同一明细码只收头一行，其余挂起。
                    if (!seenItemCodes.add(code)) {
                        invalid = "批内明细码重复：" + code;
                    }
                }
                if (invalid != null) {
                    hanged.add(new HangRow(r, rowNo, invalid));
                } else {
                    accepted.add(r);
                    stamp(r, batchKey, rowNo, STATUS_OK, null);
                }
            }

            int ok = accepted.size();
            int fail = hanged.size();
            int total = ok + fail;
            if (total == 0) {
                return 0;
            }

            // 批次头先落库"认领"本批（batch_no 唯一索引是多实例并发重发的最后一道闸）；
            // 此时尚无明细写入，唯一键冲突被捕获后本次事务照常提交为空操作。
            TFlogDeclareBatch head = newHead(batchKey, total, ok, fail);
            try {
                this.flogDeclareBatchMapper.insert(head);
            } catch (DuplicateKeyException dup) {
                // 另一实例抢先收过同一批次：本次什么都不落，回头一次结论。
                // FOR UPDATE 当前读：本事务首次快照读可能建立在胜方提交之前，
                // MySQL RR 下普通 SELECT 会读不到，必须强制读最新已提交版本。
                TFlogDeclareBatch winner = this.flogDeclareBatchMapper.selectOne(
                        new QueryWrapper<TFlogDeclareBatch>()
                                .eq("batch_no", batchKey)
                                .eq("del_flag", 0)
                                .last("FOR UPDATE"));
                return winner == null || winner.getOkCount() == null
                        ? 0 : winner.getOkCount().intValue();
            }
            // 头与明细同一事务：随后任一行写失败整体回滚，并发叠上回滚时两处结果必然一致。
            for (TFlogDeclareRow r : accepted) {
                this.flogDeclareRowMapper.insert(r);
            }
            for (HangRow h : hanged) {
                stamp(h.row, batchKey, h.rowNo, STATUS_FAIL, h.reason);
                this.flogDeclareRowMapper.insert(h.row);
            }
            return ok;
        }
    }

    @Override
    public List<TFlogDeclareRow> listErrors(String batchNo) {
        if (isBlank(batchNo)) {
            return new ArrayList<TFlogDeclareRow>();
        }
        List<TFlogDeclareRow> errs = this.flogDeclareRowMapper.selectList(new QueryWrapper<TFlogDeclareRow>()
                .eq("batch_no", batchNo.trim())
                .eq("status", STATUS_FAIL)
                .eq("del_flag", 0));
        if (errs == null || errs.isEmpty()) {
            return new ArrayList<TFlogDeclareRow>();
        }
        errs.sort(new Comparator<TFlogDeclareRow>() {
            @Override
            public int compare(TFlogDeclareRow a, TFlogDeclareRow b) {
                int ra = a.getRowNo() == null ? 0 : a.getRowNo().intValue();
                int rb = b.getRowNo() == null ? 0 : b.getRowNo().intValue();
                return ra - rb;
            }
        });
        return errs;
    }

    @Override
    public TFlogDeclareBatch selectBatch(String batchNo) {
        if (isBlank(batchNo)) {
            return null;
        }
        return this.flogDeclareBatchMapper.selectOne(new QueryWrapper<TFlogDeclareBatch>()
                .eq("batch_no", batchNo.trim())
                .eq("del_flag", 0));
    }

    /** 空串判定：批次号/明细码的纯空白串按"缺失"处理 */
    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** 单行校验：返回 null 表示合法；否则返回挂起原因（原因写入挂起明细备注，便于运维定位） */
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
        if (r.getQty().compareTo(java.math.BigDecimal.ZERO) <= 0) {
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

    /** 落库前统一盖章：批次号、行号、状态、备注一处定稿，软删标记缺省补 0 */
    private static void stamp(TFlogDeclareRow r, String batchNo, int rowNo, int status, String reason) {
        r.setBatchNo(batchNo);
        r.setRowNo(Integer.valueOf(rowNo));
        r.setStatus(Integer.valueOf(status));
        // 已收行清掉历史备注（同一实体可能被复用，避免残留上次挂起原因）；挂起行写入原因。
        r.setRemark(reason);
        if (r.getDelFlag() == null) {
            r.setDelFlag(0);
        }
        r.setUpdateTime(new java.util.Date());
    }

    /** 批次头组装：总数/成功/挂起同源于本次逐行核收的计数 */
    private static TFlogDeclareBatch newHead(String batchNo, int total, int ok, int fail) {
        TFlogDeclareBatch h = new TFlogDeclareBatch();
        h.setBatchNo(batchNo);
        h.setTotalCount(Integer.valueOf(total));
        h.setOkCount(Integer.valueOf(ok));
        h.setFailCount(Integer.valueOf(fail));
        h.setStatus(Integer.valueOf(BATCH_DONE));
        h.setDelFlag(0);
        h.setUpdateTime(new java.util.Date());
        return h;
    }

    /** 挂起行暂存：连同归一后的行号与挂起原因，待批次头认领成功后随明细一起落库 */
    private static final class HangRow {
        private final TFlogDeclareRow row;
        private final int rowNo;
        private final String reason;

        private HangRow(TFlogDeclareRow row, int rowNo, String reason) {
            this.row = row;
            this.rowNo = rowNo;
            this.reason = reason;
        }
    }

    private Object lockOf(String batchKey) {
        return this.batchLocks[(batchKey.hashCode() & 0x7fffffff) & (LOCK_STRIPES - 1)];
    }

}
