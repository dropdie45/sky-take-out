package com.sky.service.impl;

import com.sky.context.BaseContext;
import com.sky.controller.user.OrderController;
import com.sky.dto.OrdersPaymentDTO;
import com.sky.entity.Orders;
import com.sky.exception.OrderBusinessException;
import com.sky.mapper.OrderMapper;
import com.sky.websocket.WebSocketServer;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrderPaymentTest {
    private OrderMapper mapper;
    private OrderServiceImpl service;
    private Orders order;
    private OrdersPaymentDTO dto;
    private WebSocketServer webSocketServer;

    @BeforeEach
    void setUp() {
        BaseContext.setCurrentId(10L);
        mapper = mock(OrderMapper.class);
        service = new OrderServiceImpl();
        ReflectionTestUtils.setField(service, "orderMapper", mapper);
        webSocketServer = mock(WebSocketServer.class);
        ReflectionTestUtils.setField(service, "webSocketServer", webSocketServer);
        order = Orders.builder().id(1L).number("20261030001").userId(10L)
                .status(Orders.PENDING_PAYMENT).payStatus(Orders.UN_PAID).build();
        dto = new OrdersPaymentDTO();
        dto.setOrderNumber(order.getNumber());
        dto.setPayMethod(1);
        when(mapper.getByNumberAndUserId(order.getNumber(), 10L)).thenReturn(order);
        when(mapper.markPaid(any())).thenReturn(1);
    }

    @AfterEach
    void cleanUp() {
        BaseContext.removeCurrentId();
    }

    @Test
    void controllerCompletesMockPaymentAndReturnsNoWechatCredentials() {
        OrderController controller = new OrderController();
        ReflectionTestUtils.setField(controller, "orderService", service);
        assertEquals(1, controller.payment(dto).getCode());
        assertNull(service.payment(dto).getPaySign());
        ArgumentCaptor<Orders> update = ArgumentCaptor.forClass(Orders.class);
        verify(mapper).markPaid(update.capture());
        assertEquals(Orders.TO_BE_CONFIRMED, update.getValue().getStatus());
        assertEquals(Orders.PAID, update.getValue().getPayStatus());
        assertEquals(1, update.getValue().getPayMethod());
        assertEquals(10L, update.getValue().getUserId());
        assertNotNull(update.getValue().getCheckoutTime());
    }

    @Test
    void missingOrOtherUsersOrderCannotBePaid() {
        when(mapper.getByNumberAndUserId(order.getNumber(), 10L)).thenReturn(null);
        assertThrows(OrderBusinessException.class, () -> service.payment(dto));
        assertThrows(OrderBusinessException.class, () -> service.paySuccess(order.getNumber()));
        verify(mapper, never()).markPaid(any());
    }

    @Test
    void cancelledOrderCannotBePaid() {
        order.setStatus(Orders.CANCELLED);
        assertThrows(OrderBusinessException.class, () -> service.payment(dto));
        assertThrows(OrderBusinessException.class, () -> service.paySuccess(order.getNumber()));
        verify(mapper, never()).markPaid(any());
    }

    @Test
    void alreadyPaidOrderIsIdempotent() {
        order.setPayStatus(Orders.PAID);
        order.setStatus(Orders.CONFIRMED);
        service.payment(dto);
        service.paySuccess(order.getNumber());
        verify(mapper, never()).markPaid(any());
        verifyNoInteractions(webSocketServer);
    }

    @Test
    void validatesPaymentInputAndLogin() {
        dto.setPayMethod(2);
        assertThrows(OrderBusinessException.class, () -> service.payment(dto));
        dto.setPayMethod(1);
        dto.setOrderNumber(" ");
        assertThrows(OrderBusinessException.class, () -> service.payment(dto));
        BaseContext.removeCurrentId();
        assertThrows(OrderBusinessException.class, () -> service.paySuccess(order.getNumber()));
        verify(mapper, never()).markPaid(any());
    }

    @Test
    void concurrentCancellationCannotBeOverwritten() {
        when(mapper.markPaid(any())).thenReturn(0);
        Orders cancelled = Orders.builder().status(Orders.CANCELLED).payStatus(Orders.UN_PAID).build();
        when(mapper.getByNumberAndUserId(order.getNumber(), 10L)).thenReturn(order, cancelled);
        assertThrows(OrderBusinessException.class, () -> service.paySuccess(order.getNumber()));
    }

    @Test
    void concurrentSuccessfulPaymentIsIdempotent() {
        when(mapper.markPaid(any())).thenReturn(0);
        Orders paid = Orders.builder().status(Orders.TO_BE_CONFIRMED).payStatus(Orders.PAID).build();
        when(mapper.getByNumberAndUserId(order.getNumber(), 10L)).thenReturn(order, paid);
        assertDoesNotThrow(() -> service.paySuccess(order.getNumber()));
        verifyNoInteractions(webSocketServer);
    }

    @Test
    void newOrderNotificationWaitsForCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.paySuccess(order.getNumber());
            verifyNoInteractions(webSocketServer);
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
            ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
            verify(webSocketServer).sendToAllClient(message.capture());
            JSONObject json = JSONObject.parseObject(message.getValue());
            assertEquals(1, json.getIntValue("type"));
            assertEquals(order.getId(), json.getLong("orderId"));
            assertEquals("订单号：" + order.getNumber(), json.getString("content"));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void rollbackDoesNotNotifyMerchant() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.paySuccess(order.getNumber());
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
            verifyNoInteractions(webSocketServer);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void reminderSupportsActivePaidOrders() {
        when(mapper.getById(order.getId())).thenReturn(order);
        order.setPayStatus(Orders.PAID);
        for (int status = Orders.TO_BE_CONFIRMED; status <= Orders.DELIVERY_IN_PROGRESS; status++) {
            order.setStatus(status);
            service.reminder(order.getId());
        }
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(webSocketServer, times(3)).sendToAllClient(message.capture());
        JSONObject json = JSONObject.parseObject(message.getValue());
        assertEquals(2, json.getIntValue("type"));
        assertEquals(order.getId(), json.getLong("orderId"));
        assertEquals("订单号：" + order.getNumber(), json.getString("content"));
    }

    @Test
    void reminderRejectsMissingOtherUsersAndInactiveOrders() {
        assertThrows(OrderBusinessException.class, () -> service.reminder(null));
        assertThrows(OrderBusinessException.class, () -> service.reminder(999L));
        when(mapper.getById(order.getId())).thenReturn(order);
        order.setUserId(20L);
        assertThrows(OrderBusinessException.class, () -> service.reminder(order.getId()));
        order.setUserId(10L);
        assertThrows(OrderBusinessException.class, () -> service.reminder(order.getId()));
        order.setPayStatus(Orders.PAID);
        order.setStatus(Orders.COMPLETED);
        assertThrows(OrderBusinessException.class, () -> service.reminder(order.getId()));
        order.setStatus(Orders.CANCELLED);
        assertThrows(OrderBusinessException.class, () -> service.reminder(order.getId()));
        BaseContext.removeCurrentId();
        assertThrows(OrderBusinessException.class, () -> service.reminder(order.getId()));
        verifyNoInteractions(webSocketServer);
    }
}
