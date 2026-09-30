package com.fc.v2.service.impl;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fc.v2.common.support.ConvertUtil;
import com.fc.v2.mapper.auto.TFlogApplyCardMapper;
import com.fc.v2.model.auto.TFlogApplyCard;
import com.fc.v2.model.auto.TFlogStandDoc;
import com.fc.v2.service.ITFlogApplyCardService;
import com.fc.v2.service.support.TFlogStandDocPolicy;
import com.fc.v2.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 伐区采伐申请建档卡Service业务层处理
 *
 * <p>编制单位在册底档相关口径（在册校验、site_no 回填、三线上限定档）
 * 一律走 {@link TFlogStandDocPolicy}，本类不得另写一套——插入、修改、
 * 下游汇总看到的必须是同一个规矩。</p>
 *
 * @author fuce
 * @date 2026-09-12
 */
@Service
public class TFlogApplyCardServiceImpl extends ServiceImpl<TFlogApplyCardMapper, TFlogApplyCard> implements ITFlogApplyCardService {

    @Autowired
    private TFlogStandDocPolicy standDocPolicy;

    @Override
    public TFlogApplyCard selectTFlogApplyCardById(Long id) {
        return this.baseMapper.selectOne(new QueryWrapper<TFlogApplyCard>()
                .eq("id", id)
                .eq("del_flag", 0));
    }

    @Override
    public List<TFlogApplyCard> selectTFlogApplyCardList(Wrapper<TFlogApplyCard> queryWrapper) {
        // 调用方的查询条件照单全收（含控制器 startPage 的分页），只统一补上"未删除"口径，
        // 不再硬编码 status=0、另起一页 10 条那种无视入参的写法。
        QueryWrapper<TFlogApplyCard> wrapper;
        if (queryWrapper instanceof QueryWrapper) {
            wrapper = (QueryWrapper<TFlogApplyCard>) queryWrapper;
        } else {
            wrapper = new QueryWrapper<TFlogApplyCard>();
        }
        wrapper.eq("del_flag", 0);
        return this.baseMapper.selectList(wrapper);
    }

    @Override
    public int insertTFlogApplyCard(TFlogApplyCard record) {
        if (record == null) {
            return 0;
        }

        // 既有口径：建档卡创建者取建档卡号（保持旧行为，勿擅改）
        record.setCreateBy(record.getBillNo());
        // 编制单位必须在册（存在、未删、未收回），底档字号与取用顺位同源回填。
        TFlogStandDoc arch = this.standDocPolicy.requireActiveStandDoc(toSiteId(record.getSiteId()));
        if (arch == null) {
            return 0;
        }
        record.setSiteNo(arch.getSiteNo());
        record.setGradeLevel(Integer.valueOf(this.standDocPolicy.gradeLevel(arch, record.getQty())));

        if (StringUtils.isNotEmpty(record.getBillNo())) {
            Integer dupCnt = this.baseMapper.selectCount(new QueryWrapper<TFlogApplyCard>()
                    .eq("bill_no", record.getBillNo()).eq("del_flag", 0));
            if (dupCnt != null && dupCnt > 0) {
                return 0;
            }
        }
        record.setDelFlag(0);
        try {
            return this.baseMapper.insert(record);
        } catch (DuplicateKeyException concurrentDup) {
            // 同号并发抢注：活跃单号唯一索引兜底，后来者拒入。
            return 0;
        }
    }

    @Override
    public int updateTFlogApplyCard(TFlogApplyCard record) {
        if (record == null || record.getId() == null) {
            return 0;
        }
        TFlogApplyCard existed = this.baseMapper.selectOne(new QueryWrapper<TFlogApplyCard>()
                .eq("id", record.getId())
                .eq("del_flag", 0));
        if (existed == null) {
            return 0;
        }

        if (StringUtils.isNotEmpty(record.getBillNo())) {
            Integer dupCnt = this.baseMapper.selectCount(new QueryWrapper<TFlogApplyCard>()
                    .eq("bill_no", record.getBillNo()).ne("id", record.getId()).eq("del_flag", 0));
            if (dupCnt != null && dupCnt > 0) {
                return 0;
            }
        }

        // 改挂单位时按新底档重新校验在册；未传单位则沿用原单位。两条路同一个口径。
        Integer siteIdVal = record.getSiteId() != null ? record.getSiteId() : existed.getSiteId();
        TFlogStandDoc arch = this.standDocPolicy.requireActiveStandDoc(toSiteId(siteIdVal));
        if (arch == null) {
            return 0;
        }
        record.setSiteId(siteIdVal);
        record.setSiteNo(arch.getSiteNo());
        // 数量未传视为不变，按现值重新定档；保证库里 grade_level 与 qty 永远同口径。
        java.math.BigDecimal qty = record.getQty() != null ? record.getQty() : existed.getQty();
        record.setGradeLevel(Integer.valueOf(this.standDocPolicy.gradeLevel(arch, qty)));

        record.setUpdateTime(new Date());
        try {
            return this.baseMapper.update(record, new UpdateWrapper<TFlogApplyCard>()
                    .eq("id", record.getId())
                    .eq("del_flag", 0));
        } catch (DuplicateKeyException concurrentDup) {
            return 0;
        }
    }

    /** 建档卡 site_id 为 Integer，底档主键为 Long，边界处统一转换 */
    private static Long toSiteId(Integer siteId) {
        return siteId == null ? null : siteId.longValue();
    }

    @Override
    public int deleteTFlogApplyCardByIds(String ids) {
        Long[] idArr = ConvertUtil.toLongArray(ids);
        if (idArr == null || idArr.length == 0) {
            return 0;
        }
        // 软删留痕，旧账永远查得回来；不动物理行。
        return this.baseMapper.update(null, new UpdateWrapper<TFlogApplyCard>()
                .set("del_flag", 1)
                .in("id", Arrays.asList(idArr))
                .eq("del_flag", 0));
    }

    @Override
    public int deleteTFlogApplyCardById(Long id) {
        if (id == null) {
            return 0;
        }
        return this.baseMapper.update(null, new UpdateWrapper<TFlogApplyCard>()
                .set("del_flag", 1)
                .eq("id", id)
                .eq("del_flag", 0));
    }
}
