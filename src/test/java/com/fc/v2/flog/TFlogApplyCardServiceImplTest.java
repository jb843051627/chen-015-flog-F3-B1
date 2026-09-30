package com.fc.v2.flog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.fc.v2.mapper.auto.TFlogApplyCardMapper;
import com.fc.v2.mapper.auto.TFlogStandDocMapper;
import com.fc.v2.model.auto.TFlogApplyCard;
import com.fc.v2.model.auto.TFlogStandDoc;
import com.fc.v2.service.impl.TFlogApplyCardServiceImpl;

/**
 * 编制单位在册底档口径测试：存在性校验与阈值取用同源，已收回/已删底档一律不认。
 */
class TFlogApplyCardServiceImplTest {

    @Mock
    private TFlogApplyCardMapper applyCardMapper;

    @Mock
    private TFlogStandDocMapper standDocMapper;

    @InjectMocks
    private TFlogApplyCardServiceImpl service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // ServiceImpl#baseMapper 泛型字段擦除后与其它 BaseMapper 同型，显式钉死注入对象。
        org.springframework.test.util.ReflectionTestUtils.setField(service, "baseMapper", applyCardMapper);
        when(applyCardMapper.selectCount(any())).thenReturn(0);
    }

    private TFlogStandDoc arch(int status) {
        TFlogStandDoc a = new TFlogStandDoc();
        a.setId(0L);
        a.setSiteNo("DC00");
        a.setStatus(Integer.valueOf(status));
        a.setDelFlag(0);
        a.setTh1Max(new BigDecimal("40"));
        a.setTh2Max(new BigDecimal("120"));
        a.setTh3Max(new BigDecimal("300"));
        return a;
    }

    private TFlogApplyCard card() {
        TFlogApplyCard c = new TFlogApplyCard();
        c.setId(100L);
        c.setBillNo("BK-1");
        c.setSiteId(0);
        c.setQty(new BigDecimal("50"));
        return c;
    }

    @Test
    void registeredStandDoc_siteNoAndThresholdsComeFromSameSource() {
        when(standDocMapper.selectOne(any())).thenReturn(arch(0));
        when(applyCardMapper.insert(any())).thenReturn(1);

        TFlogApplyCard c = card();
        int rows = service.insertTFlogApplyCard(c);

        assertEquals(1, rows);
        assertEquals("DC00", c.getSiteNo(), "在册底档字号同源回填");
        assertEquals(2, c.getGradeLevel().intValue(), "50 落在 (40,120]，中度第 2 档");
    }

    @Test
    void withdrawnStandDoc_rejected_noSecondBareLookup() {
        // 库里已收回（status=1）：带在册过滤的查询查不到，整张申请卡拒收。
        when(standDocMapper.selectOne(any())).thenReturn(null);

        assertEquals(0, service.insertTFlogApplyCard(card()));
        // 只准有"在册口径"这一次查询，不许再按主键裸查把已收回底档的阈值取回来。
        verify(standDocMapper, never()).selectById(any());
        verify(applyCardMapper, never()).insert(any());
    }
}
