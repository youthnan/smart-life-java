package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.service.ISeckillVoucherService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoucherOrderServiceImplTest {

    @Spy
    @InjectMocks
    private VoucherOrderServiceImpl voucherOrderService;

    @Mock
    private ISeckillVoucherService seckillVoucherService;

    @SuppressWarnings("unchecked")
    @Test
    void createVoucherOrder_shouldReturnWhenDuplicateOrder() {
        VoucherOrder order = new VoucherOrder();
        order.setUserId(1L);
        order.setVoucherId(10L);

        QueryChainWrapper<VoucherOrder> queryChain = mock(QueryChainWrapper.class);
        doReturn(queryChain).when(voucherOrderService).query();
        when(queryChain.eq(any(), any())).thenReturn(queryChain);
        when(queryChain.count()).thenReturn(1);

        voucherOrderService.createVoucherOrder(order);

        verify(seckillVoucherService, never()).update();
        verify(voucherOrderService, never()).save(any(VoucherOrder.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void createVoucherOrder_shouldReturnWhenStockNotEnough() {
        VoucherOrder order = new VoucherOrder();
        order.setUserId(1L);
        order.setVoucherId(10L);

        QueryChainWrapper<VoucherOrder> queryChain = mock(QueryChainWrapper.class);
        doReturn(queryChain).when(voucherOrderService).query();
        when(queryChain.eq(any(), any())).thenReturn(queryChain);
        when(queryChain.count()).thenReturn(0);

        UpdateChainWrapper<SeckillVoucher> updateChain = mock(UpdateChainWrapper.class);
        when(seckillVoucherService.update()).thenReturn(updateChain);
        when(updateChain.setSql(any())).thenReturn(updateChain);
        when(updateChain.eq(any(), any())).thenReturn(updateChain);
        when(updateChain.gt(any(), any())).thenReturn(updateChain);
        when(updateChain.update()).thenReturn(false);

        voucherOrderService.createVoucherOrder(order);

        verify(voucherOrderService, never()).save(any(VoucherOrder.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void createVoucherOrder_shouldSaveWhenAllChecksPass() {
        VoucherOrder order = new VoucherOrder();
        order.setId(100L);
        order.setUserId(1L);
        order.setVoucherId(10L);

        QueryChainWrapper<VoucherOrder> queryChain = mock(QueryChainWrapper.class);
        doReturn(queryChain).when(voucherOrderService).query();
        when(queryChain.eq(any(), any())).thenReturn(queryChain);
        when(queryChain.count()).thenReturn(0);

        UpdateChainWrapper<SeckillVoucher> updateChain = mock(UpdateChainWrapper.class);
        when(seckillVoucherService.update()).thenReturn(updateChain);
        when(updateChain.setSql(any())).thenReturn(updateChain);
        when(updateChain.eq(any(), any())).thenReturn(updateChain);
        when(updateChain.gt(any(), any())).thenReturn(updateChain);
        when(updateChain.update()).thenReturn(true);
        doReturn(true).when(voucherOrderService).save(order);

        voucherOrderService.createVoucherOrder(order);

        verify(voucherOrderService).save(order);
    }
}
