package com.fc.v2.service.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fc.v2.mapper.auto.TFlogStandDocMapper;
import com.fc.v2.model.auto.TFlogStandDoc;

/**
 * 编制单位在册底档口径直测：全 flog 域唯一定档/在册判定出处。
 */
@ExtendWith(MockitoExtension.class)
class TFlogStandDocPolicyTest {

    @Mock
    private TFlogStandDocMapper flogStandDocMapper;
    @InjectMocks
    private TFlogStandDocPolicy policy;

    private TFlogStandDoc doc(Integer status) {
        TFlogStandDoc d = new TFlogStandDoc();
        d.setId(0L);
        d.setStatus(status);
        d.setTh1Max(new BigDecimal("40"));
        d.setTh2Max(new BigDecimal("120"));
        d.setTh3Max(new BigDecimal("300"));
        return d;
    }

    @Test
    void 已收回的底档查不出来() {
        when(flogStandDocMapper.selectOne(any())).thenReturn(null);
        assertNull(policy.requireActiveStandDoc(1L));
    }

    @Test
    void 分档边界_等于上限算高一档() {
        TFlogStandDoc d = doc(0);
        assertEquals(1, policy.gradeLevel(d, new BigDecimal("40")));
        assertEquals(2, policy.gradeLevel(d, new BigDecimal("40.01")));
        assertEquals(2, policy.gradeLevel(d, new BigDecimal("120")));
        assertEquals(3, policy.gradeLevel(d, new BigDecimal("300")));
        assertEquals(4, policy.gradeLevel(d, new BigDecimal("300.01")));
        assertEquals(TFlogStandDocPolicy.GRADE_NONE, policy.gradeLevel(d, null));
        assertEquals(TFlogStandDocPolicy.GRADE_NONE, policy.gradeLevel(null, new BigDecimal("10")));
    }
}
