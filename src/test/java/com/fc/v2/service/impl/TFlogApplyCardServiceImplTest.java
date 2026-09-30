package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TFlogApplyCardMapper;
import com.fc.v2.model.auto.TFlogApplyCard;
import com.fc.v2.model.auto.TFlogStandDoc;
import com.fc.v2.service.support.TFlogStandDocPolicy;

/**
 * 建档卡服务测试：在册校验/定档全部委托统一口径 {@link TFlogStandDocPolicy}，
 * 列表尊重入参且只出未删，删除走软删。
 */
@ExtendWith(MockitoExtension.class)
class TFlogApplyCardServiceImplTest {

    @Mock
    private TFlogApplyCardMapper baseMapper;
    @Mock
    private TFlogStandDocPolicy standDocPolicy;
    @InjectMocks
    private TFlogApplyCardServiceImpl service;

    private TFlogStandDoc activeDoc() {
        TFlogStandDoc d = new TFlogStandDoc();
        d.setId(0L);
        d.setSiteNo("DC00");
        return d;
    }

    @Test
    void 单位不在册_拒入() {
        TFlogApplyCard r = new TFlogApplyCard();
        r.setSiteId(1);
        when(standDocPolicy.requireActiveStandDoc(1L)).thenReturn(null);
        assertEquals(0, service.insertTFlogApplyCard(r));
        verify(baseMapper, never()).insert(any());
    }

    @Test
    void 在册卡_字号与档位同源回填() {
        when(standDocPolicy.requireActiveStandDoc(0L)).thenReturn(activeDoc());
        when(standDocPolicy.gradeLevel(any(TFlogStandDoc.class), any())).thenReturn(2);
        when(baseMapper.selectCount(any())).thenReturn(0);
        when(baseMapper.insert(any())).thenReturn(1);

        TFlogApplyCard r = new TFlogApplyCard();
        r.setSiteId(0);
        r.setBillNo("B-1");
        r.setQty(new BigDecimal("120"));
        assertEquals(1, service.insertTFlogApplyCard(r));
        assertEquals("DC00", r.getSiteNo());
        assertEquals(2, r.getGradeLevel().intValue());
    }

    @Test
    void 更新已删卡_拒更() {
        when(baseMapper.selectOne(any())).thenReturn(null);
        TFlogApplyCard r = new TFlogApplyCard();
        r.setId(99L);
        assertEquals(0, service.updateTFlogApplyCard(r));
        verify(baseMapper, never()).update(any(), any());
    }

    @Test
    void 删除走软删_不动物理行() {
        when(baseMapper.update(any(), any())).thenReturn(2);
        int n = service.deleteTFlogApplyCardByIds("1,2");
        assertEquals(2, n);
        verify(baseMapper, never()).deleteBatchIds(any());
        verify(baseMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void 列表保留入参条件且统一补未删() {
        QueryWrapper<TFlogApplyCard> qw = new QueryWrapper<TFlogApplyCard>().eq("bill_no", "X");
        when(baseMapper.selectList(any())).thenAnswer(inv -> {
            QueryWrapper<TFlogApplyCard> w = inv.getArgument(0);
            String seg = w.getSqlSegment();
            if (!seg.contains("bill_no") || !seg.contains("del_flag")) {
                throw new AssertionError("查询条件丢了入参或缺统一未删口径: " + seg);
            }
            return Arrays.asList(new TFlogApplyCard());
        });
        List<TFlogApplyCard> list = service.selectTFlogApplyCardList(qw);
        assertEquals(1, list.size());
    }
}
