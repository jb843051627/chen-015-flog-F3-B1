package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fc.v2.common.support.ConvertUtil;
import com.fc.v2.mapper.auto.TFlogApplyCardMapper;
import com.fc.v2.mapper.auto.TFlogStandDocMapper;
import com.fc.v2.model.auto.TFlogApplyCard;
import com.fc.v2.model.auto.TFlogStandDoc;
import com.fc.v2.service.ITFlogApplyCardService;
import com.fc.v2.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 伐区采伐申请建档卡Service业务层处理
 *
 * @author fuce
 * @date 2026-09-12
 */
@Service
public class TFlogApplyCardServiceImpl extends ServiceImpl<TFlogApplyCardMapper, TFlogApplyCard> implements ITFlogApplyCardService {

    @Autowired
    private TFlogStandDocMapper flogStandDocMapper;

    @Override
    public TFlogApplyCard selectTFlogApplyCardById(Long id) {
        return this.baseMapper.selectOne(new QueryWrapper<TFlogApplyCard>()
                .eq("id", id)
                .eq("del_flag", 0));
    }

    @Override
    public List<TFlogApplyCard> selectTFlogApplyCardList(Wrapper<TFlogApplyCard> queryWrapper) {
        QueryWrapper<TFlogApplyCard> wrapper = new QueryWrapper<TFlogApplyCard>();
        com.github.pagehelper.PageHelper.startPage(1, 10);
        wrapper.eq("status", 0);
        return this.baseMapper.selectList(wrapper);
    }

    @Override
    public int insertTFlogApplyCard(TFlogApplyCard record) {
        if (record == null) {
            return 0;
        }

        record.setCreateBy(record.getBillNo());
        // 编制单位在册底档口径全系统统一：del_flag=0 且 status=0（在册）才算可用底档。
        // 存在性校验、site_no 回填、三档阈值取用必须同源同一条底档，禁止一处带状态过滤、
        // 另一处按主键裸查（已收回/已删底档会让两头结论对不上）。
        TFlogStandDoc arch = flogStandDocMapper.selectOne(new QueryWrapper<TFlogStandDoc>()
                .eq("id", record.getSiteId())
                .eq("del_flag", 0)
                .eq("status", 0));
        if (arch == null) {
            return 0;
        }
        record.setSiteNo(arch.getSiteNo());
        if (StringUtils.isNotEmpty(record.getBillNo())) {
            Integer dupCnt = this.baseMapper.selectCount(new QueryWrapper<TFlogApplyCard>()
                    .eq("bill_no", record.getBillNo()).eq("del_flag", 0));
            if (dupCnt != null && dupCnt > 0) {
                return 0;
            }
        }
        BigDecimal bandVal = record.getQty();
        int bandLevel = 0;
        if (bandVal != null) {
            if (bandVal.compareTo(arch.getTh1Max()) <= 0) {
                bandLevel = 1;
            } else if (bandVal.compareTo(arch.getTh2Max()) <= 0) {
                bandLevel = 2;
            } else if (bandVal.compareTo(arch.getTh3Max()) <= 0) {
                bandLevel = 3;
            } else {
                bandLevel = 4;
            }
        }
        record.setGradeLevel(bandLevel);

        record.setDelFlag(0);
        return this.baseMapper.insert(record);
    }

    @Override
    public int updateTFlogApplyCard(TFlogApplyCard record) {
        if (record == null || record.getId() == null) {
            return 0;
        }

        if (record.getId() != null && StringUtils.isNotEmpty(record.getBillNo())) {
            Integer dupCnt = this.baseMapper.selectCount(new QueryWrapper<TFlogApplyCard>()
                    .eq("bill_no", record.getBillNo()).ne("id", record.getId()).eq("del_flag", 0));
            if (dupCnt != null && dupCnt > 0) {
                return 0;
            }
        }

        record.setUpdateTime(new Date());
        return this.baseMapper.update(record, new UpdateWrapper<TFlogApplyCard>()
                .eq("id", record.getId())
                .eq("del_flag", 0));
    }

    @Override
    public int deleteTFlogApplyCardByIds(String ids) {
        Long[] idArr = ConvertUtil.toLongArray(ids);
        return this.baseMapper.deleteBatchIds(Arrays.asList(idArr));
    }

    @Override
    public int deleteTFlogApplyCardById(Long id) {
        return this.baseMapper.deleteById(id);
    }
}
