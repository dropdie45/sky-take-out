package com.sky.task;

import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import org.apache.ibatis.builder.annotation.MapperAnnotationBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderTaskTest {
    private OrderMapper mapper;
    private OrderTask task;

    @BeforeEach
    void setUp() {
        mapper = mock(OrderMapper.class);
        task = new OrderTask();
        ReflectionTestUtils.setField(task, "orderMapper", mapper);
    }

    @Test
    void cancelsOrdersOlderThanFifteenMinutesWithConditionalUpdate() {
        LocalDateTime before = LocalDateTime.now().minusMinutes(15);
        when(mapper.getByStatusAndOrderTime(eq(Orders.PENDING_PAYMENT), any()))
                .thenReturn(Collections.singletonList(Orders.builder().id(1L).build()));
        task.processTimeOutOrder();
        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).getByStatusAndOrderTime(eq(Orders.PENDING_PAYMENT), cutoff.capture());
        assertFalse(cutoff.getValue().isBefore(before));
        assertFalse(cutoff.getValue().isAfter(LocalDateTime.now().minusMinutes(15)));
        ArgumentCaptor<Orders> update = ArgumentCaptor.forClass(Orders.class);
        verify(mapper).cancelTimedOutOrder(update.capture());
        assertEquals(1L, update.getValue().getId());
        assertEquals(Orders.CANCELLED, update.getValue().getStatus());
        assertEquals("订单超时，自动取消", update.getValue().getCancelReason());
        assertNotNull(update.getValue().getCancelTime());
        verify(mapper, never()).update(any());
    }

    @Test
    void completesOldDeliveryOrdersAndSetsDeliveryTime() {
        LocalDateTime before = LocalDateTime.now().minusMinutes(60);
        when(mapper.getByStatusAndOrderTime(eq(Orders.DELIVERY_IN_PROGRESS), any()))
                .thenReturn(Collections.singletonList(Orders.builder().id(2L).build()));
        task.processDeliveryOrder();
        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).getByStatusAndOrderTime(eq(Orders.DELIVERY_IN_PROGRESS), cutoff.capture());
        assertFalse(cutoff.getValue().isBefore(before));
        assertFalse(cutoff.getValue().isAfter(LocalDateTime.now().minusMinutes(60)));
        ArgumentCaptor<Orders> update = ArgumentCaptor.forClass(Orders.class);
        verify(mapper).completeDeliveryOrder(update.capture());
        assertEquals(Orders.COMPLETED, update.getValue().getStatus());
        assertNotNull(update.getValue().getDeliveryTime());
        verify(mapper, never()).update(any());
    }

    @Test
    void emptyOrNullResultsDoNotWriteOrders() {
        when(mapper.getByStatusAndOrderTime(eq(Orders.PENDING_PAYMENT), any()))
                .thenReturn(Collections.emptyList());
        task.processTimeOutOrder();
        task.processDeliveryOrder();
        verify(mapper, never()).cancelTimedOutOrder(any());
        verify(mapper, never()).completeDeliveryOrder(any());
    }

    @Test
    void mapperBindsCutoffParameterAndGuardsConcurrentStateChanges() throws Exception {
        Configuration configuration = new Configuration();
        new MapperAnnotationBuilder(configuration, OrderMapper.class).parse();
        LocalDateTime cutoff = LocalDateTime.now();
        ParamNameResolver resolver = new ParamNameResolver(configuration,
                OrderMapper.class.getMethod("getByStatusAndOrderTime", Integer.class, LocalDateTime.class));
        Object parameters = resolver.getNamedParams(new Object[]{Orders.PENDING_PAYMENT, cutoff});
        BoundSql query = configuration.getMappedStatement(OrderMapper.class.getName() + ".getByStatusAndOrderTime")
                .getBoundSql(parameters);
        for (org.apache.ibatis.mapping.ParameterMapping mapping : query.getParameterMappings()) {
            assertNotNull(((Map<?, ?>) parameters).get(mapping.getProperty()));
        }
        assertEquals(cutoff, ((Map<?, ?>) parameters).get("orderTime"));
        String cancelSql = configuration.getMappedStatement(OrderMapper.class.getName() + ".cancelTimedOutOrder")
                .getBoundSql(new Orders()).getSql();
        assertTrue(cancelSql.contains("status = 1 and pay_status = 0"));
        String completeSql = configuration.getMappedStatement(OrderMapper.class.getName() + ".completeDeliveryOrder")
                .getBoundSql(new Orders()).getSql();
        assertTrue(completeSql.contains("and status = 4"));
    }
}
