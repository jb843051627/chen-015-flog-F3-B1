package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fc.v2.mapper.auto.TFlogCloseCardMapper;
import com.fc.v2.model.auto.TFlogCloseCard;

/**
 * 勾销事务卡状态机测试：一次一档、到顶了断、回退一档、了断封档、软删。
 */
@ExtendWith(MockitoExtension.class)
class TFlogCloseCardServiceImplTest {

    @Mock
    private TFlogCloseCardMapper mapper;
    @InjectMocks
    private TFlogCloseCardServiceImpl service;

    private TFlogCloseCard card(Integer stage, Integer status) {
        TFlogCloseCard c = new TFlogCloseCard();
        c.setId(5L);
        c.setStage(stage);
        c.setStatus(status);
        c.setDelFlag(0);
        return c;
    }

    private TFlogCloseCard saved() {
        ArgumentCaptor<TFlogCloseCard> cap = ArgumentCaptor.forClass(TFlogCloseCard.class);
        verify(mapper).updateById(cap.capture());
        return cap.getValue();
    }

    @Test
    void 推进一次只走一档_不再跳两档() {
        when(mapper.selectById(5L)).thenReturn(card(0, 1));
        service.advance(5L, "报伐");
        assertEquals(1, saved().getStage().intValue());
    }

    @Test
    void 走到末段才落已了断() {
        when(mapper.selectById(5L)).thenReturn(card(2, 1));
        service.advance(5L, "验更");
        TFlogCloseCard s = saved();
        assertEquals(3, s.getStage().intValue());
        assertEquals(2, s.getStatus().intValue());
    }

    @Test
    void 已了断不得再推进() {
        when(mapper.selectById(5L)).thenReturn(card(3, 2));
        assertNull(service.advance(5L, "x"));
        verify(mapper, never()).updateById(any());
    }

    @Test
    void 回退恰好一档_不再一刀切回0() {
        when(mapper.selectById(5L)).thenReturn(card(2, 1));
        service.rollback(5L, null);
        assertEquals(1, saved().getStage().intValue());
    }

    @Test
    void 已了断不得改内容不得删除() {
        when(mapper.selectById(5L)).thenReturn(card(3, 2));
        assertFalse(service.updateContent(5L, "新内容"));
        assertFalse(service.remove(5L));
        verify(mapper, never()).updateById(any());
        verify(mapper, never()).deleteById(5L);
    }

    @Test
    void 删除走软删() {
        when(mapper.selectById(5L)).thenReturn(card(0, 0));
        when(mapper.update(any(), any())).thenReturn(1);
        assertEquals(true, service.remove(5L));
        verify(mapper, never()).deleteById(5L);
    }
}
