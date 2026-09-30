package com.fc.v2.service;

import java.util.List;

import com.fc.v2.model.auto.TFlogDeclareBatch;
import com.fc.v2.model.auto.TFlogDeclareRow;

/**
 * 伐区申报清册核收行 Service接口（batch-process 形状：整批提交，无单条增删改）
 *
 * @author fuce
 * @date 2026-09-14
 */
public interface ITFlogDeclareRowService {

    /** 按主键回查单行 */
    TFlogDeclareRow selectTFlogDeclareRowById(Long id);

    /**
     * 整批提交，口径如下（实现与批次头台账同源，不得在别处另算）：
     * <ul>
     *   <li>空批次（批次号空白或无明细）返回 0，不入库；</li>
     *   <li>行数超过单批上限 {@value com.fc.v2.service.impl.TFlogDeclareRowServiceImpl#BATCH_ROW_MAX}
     *       返回 -1 且整批不入库；</li>
     *   <li>逐行核收：合法行已收、非法/批内重码行挂起并记原因，不再因一行退整批；
     *       返回<strong>成功行数</strong>，挂起行数与总数可经 {@link #selectBatch(String)} 对账，
     *       恒有 成功 + 挂起 = 本批总行数；</li>
     *   <li>同一批次号重发（含并发叠发）幂等：不重复入库，直接返回头一次核收的成功行数。</li>
     * </ul>
     */
    int submitBatch(String batchNo, List<TFlogDeclareRow> rows);

    /** 该批次的挂起行明细（按行号升序） */
    List<TFlogDeclareRow> listErrors(String batchNo);

    /** 批次头台账（下游对账/翻旧账的唯一口径），无此批次返回 null */
    TFlogDeclareBatch selectBatch(String batchNo);
}
