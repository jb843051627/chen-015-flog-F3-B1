package com.fc.v2.service.support;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TFlogStandDocMapper;
import com.fc.v2.model.auto.TFlogStandDoc;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

import javax.annotation.Resource;

/**
 * 编制单位在册底档口径——全 flog 域唯一出处。
 *
 * <p>凡涉及"所属编制单位是否在册""拟采蓄积落到哪个取用顺位"的判断，
 * 一律走这里，禁止各环节各写一套（建档卡、定档、下游报表必须同源）。</p>
 *
 * <ul>
 *   <li>在册：底档存在、未删除（del_flag=0）、情形为在册（status=0）；已收回的不认。</li>
 *   <li>分档：qty≤th1→1，≤th2→2，≤th3→3，超出→4；<b>等于上限算高一档</b>。</li>
 * </ul>
 *
 * @author fuce
 * @date 2026-09-30
 */
@Component
public class TFlogStandDocPolicy {

    /** 底档情形：在册 */
    public static final int SITE_STATUS_ACTIVE = 0;
    /** 未分档（数量缺失时不落任何档） */
    public static final int GRADE_NONE = 0;

    @Resource
    private TFlogStandDocMapper flogStandDocMapper;

    /**
     * 取在册底档；不存在 / 已删除 / 已收回一律返回 null（调用方按"不可挂垫"处理）。
     */
    public TFlogStandDoc requireActiveStandDoc(Long siteId) {
        if (siteId == null) {
            return null;
        }
        return this.flogStandDocMapper.selectOne(new QueryWrapper<TFlogStandDoc>()
                .eq("id", siteId)
                .eq("status", SITE_STATUS_ACTIVE)
                .eq("del_flag", 0));
    }

    /**
     * 按在册底档三线上限定档。底档为 null 或数量缺失返回 {@link #GRADE_NONE}。
     * 等于上限算高一档（与种子底档注释及业务约定一致）。
     */
    public int gradeLevel(TFlogStandDoc standDoc, BigDecimal qty) {
        if (standDoc == null || qty == null) {
            return GRADE_NONE;
        }
        if (standDoc.getTh1Max() != null && qty.compareTo(standDoc.getTh1Max()) <= 0) {
            return 1;
        }
        if (standDoc.getTh2Max() != null && qty.compareTo(standDoc.getTh2Max()) <= 0) {
            return 2;
        }
        if (standDoc.getTh3Max() != null && qty.compareTo(standDoc.getTh3Max()) <= 0) {
            return 3;
        }
        return 4;
    }
}
