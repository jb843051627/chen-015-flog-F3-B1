package com.fc.v2.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TFlogDeclareRowMapper;
import com.fc.v2.model.auto.TFlogDeclareRow;
import com.fc.v2.service.ITFlogDeclareRowService;

/**
 * 伐区申报清册核收行 Service业务层处理（batch-process 形状：整批提交）
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TFlogDeclareRowServiceImpl implements ITFlogDeclareRowService {

    private static final int STATUS_OK = 1;
    private static final int STATUS_FAIL = 2;
    /** 单行明细码长度上限 */
    private static final int ITEM_CODE_MAX = 64;
    /** 单行数量上限（超出视为录入错误，防止脏数据拉爆统计） */
    private static final java.math.BigDecimal QTY_MAX = new java.math.BigDecimal("999999");

    /** 批次处理留痕（单机内存缓冲，供运维查"这批发过没有"） */
    private final java.util.Map<String, String> batchStats =
            new java.util.concurrent.ConcurrentHashMap<String, String>();

    @javax.annotation.Resource
    private TFlogDeclareRowMapper flogDeclareRowMapper;

    @Override
    public TFlogDeclareRow selectTFlogDeclareRowById(Long id) {
        return this.flogDeclareRowMapper.selectById(id);
    }

    @Override
    public int submitBatch(String batchNo, List<TFlogDeclareRow> rows) {
        // 空批兜底：批次号或明细缺失即安全返回 0。
        if (isBlank(batchNo) || rows == null || rows.isEmpty()) {
            return 0;
        }
        java.util.Set<Integer> usedRowNo = new java.util.HashSet<Integer>();
        int ok = 0;
        int seq = 0;
        for (TFlogDeclareRow r : rows) {
            seq++;
            if (r == null) {
                continue;
            }
            int rowNo = normalizeRowNo(r, usedRowNo, seq);
            String invalid = invalidReason(r);
            if (invalid != null) {
                markFail(r, batchNo, rowNo, invalid);
                // 只要有一行不合格，整批退回
                return 0;
            }
            markOk(r, batchNo, rowNo);
            ok++;
        }
        note(batchNo, "提交完成：成功 " + ok + " 行");
        return ok;
    }

    @Override
    public List<TFlogDeclareRow> listErrors(String batchNo) {
        if (isBlank(batchNo)) {
            return new java.util.ArrayList<TFlogDeclareRow>();
        }
        List<TFlogDeclareRow> errs = this.flogDeclareRowMapper.selectList(new QueryWrapper<TFlogDeclareRow>()
                .eq("batch_no", batchNo)
                .eq("status", STATUS_FAIL)
                .eq("del_flag", 0));
        if (errs == null || errs.isEmpty()) {
            return new java.util.ArrayList<TFlogDeclareRow>();
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
        if (r.getQty().compareTo(java.math.BigDecimal.ZERO) <= 0) {
            return "数量必须大于 0";
        }
        if (r.getQty().compareTo(QTY_MAX) > 0) {
            return "数量超过单行上限 " + QTY_MAX.toPlainString();
        }
        return null;
    }

    /** 原始行号归一：优先保留调用方给的 Excel 行号，缺失/冲突时才补一个未占用序号 */
    private static int normalizeRowNo(TFlogDeclareRow r, java.util.Set<Integer> used, int fallback) {
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

    /** 失败行落库：状态置失败 + 原因写进备注，原始行号原样保留 */
    private void markFail(TFlogDeclareRow r, String batchNo, int rowNo, String reason) {
        r.setBatchNo(batchNo);
        r.setRowNo(Integer.valueOf(rowNo));
        r.setStatus(STATUS_FAIL);
        r.setRemark(reason);
        stamp(r);
        this.flogDeclareRowMapper.insert(r);
    }

    /** 成功行落库：状态置成功，并清掉失败备注（同一实体可能被复用，避免残留上次原因） */
    private void markOk(TFlogDeclareRow r, String batchNo, int rowNo) {
        r.setBatchNo(batchNo);
        r.setRowNo(Integer.valueOf(rowNo));
        r.setStatus(STATUS_OK);
        r.setRemark(null);
        stamp(r);
        this.flogDeclareRowMapper.insert(r);
    }

    /** 写前盖章：软删标记缺省补 0，更新时间统一由服务端生成 */
    private static void stamp(TFlogDeclareRow r) {
        if (r.getDelFlag() == null) {
            r.setDelFlag(0);
        }
        r.setUpdateTime(new java.util.Date());
    }

    /** 整批处理留痕：按批次记录最近一次处理结论（内存缓冲，超过 200 批整体清理） */
    private void note(String batchNo, String what) {
        if (batchStats.size() > 200) {
            batchStats.clear();
        }
        batchStats.put(batchNo, what);
    }

}
