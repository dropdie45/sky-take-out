package com.sky.service.impl;

import com.sky.exception.BaseException;
import com.sky.mapper.*;
import com.sky.vo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkspaceServiceTest {
    private WorkspaceServiceImpl service;
    private OrderMapper orders;
    private UserMapper users;
    private DishMapper dishes;
    private SetmealMapper setmeals;

    @BeforeEach
    void setUp() {
        service = new WorkspaceServiceImpl();
        orders = mock(OrderMapper.class);
        users = mock(UserMapper.class);
        dishes = mock(DishMapper.class);
        setmeals = mock(SetmealMapper.class);
        ReflectionTestUtils.setField(service, "orderMapper", orders);
        ReflectionTestUtils.setField(service, "userMapper", users);
        ReflectionTestUtils.setField(service, "dishMapper", dishes);
        ReflectionTestUtils.setField(service, "setmealMapper", setmeals);
    }

    @Test
    void businessDataCalculatesCompletedRevenueRateAndUnitPrice() {
        when(orders.countByMap(anyMap())).thenAnswer(call -> {
            Map<String, Object> query = call.getArgument(0);
            return query.containsKey("status") ? 3 : 4;
        });
        when(orders.sumByMap(anyMap())).thenReturn(120.0);
        when(users.countByMap(anyMap())).thenAnswer(call -> {
            Map<String, Object> query = call.getArgument(0);
            assertFalse(query.containsKey("status"));
            return 2;
        });
        LocalDateTime begin = LocalDate.now().atStartOfDay();
        BusinessDataVO result = service.getBusinessData(begin, begin.plusDays(1));
        assertEquals(120.0, result.getTurnover());
        assertEquals(3, result.getValidOrderCount());
        assertEquals(0.75, result.getOrderCompletionRate());
        assertEquals(40.0, result.getUnitPrice());
        assertEquals(2, result.getNewUsers());
    }

    @Test
    void noOrdersProducesZeroMetricsAndInvalidRangeIsRejected() {
        when(orders.countByMap(anyMap())).thenReturn(0);
        LocalDateTime begin = LocalDate.now().atStartOfDay();
        BusinessDataVO result = service.getBusinessData(begin, begin.plusDays(1));
        assertEquals(0.0, result.getTurnover());
        assertEquals(0.0, result.getOrderCompletionRate());
        assertEquals(0.0, result.getUnitPrice());
        assertThrows(BaseException.class, () -> service.getBusinessData(begin, begin));
    }

    @Test
    void overviewRestrictsOrdersToTodayAndCountsEachState() {
        List<Map<String, Object>> queries = new ArrayList<>();
        when(orders.countByMap(anyMap())).thenAnswer(call -> {
            Map<String, Object> query = call.getArgument(0);
            queries.add(new HashMap<>(query));
            return query.get("status") == null ? 20 : query.get("status");
        });
        OrderOverViewVO result = service.getOrderOverView();
        assertEquals(2, result.getWaitingOrders());
        assertEquals(3, result.getDeliveredOrders());
        assertEquals(5, result.getCompletedOrders());
        assertEquals(6, result.getCancelledOrders());
        assertEquals(20, result.getAllOrders());
        for (Map<String, Object> query : queries) {
            assertEquals(LocalDate.now().atStartOfDay(), query.get("begin"));
            assertEquals(LocalDate.now().plusDays(1).atStartOfDay(), query.get("end"));
        }
    }

    @Test
    void dishAndSetmealOverviewDistinguishEnabledAndDisabled() {
        when(dishes.countByMap(anyMap())).thenAnswer(call -> {
            Map<String, Object> query = call.getArgument(0);
            return Integer.valueOf(1).equals(query.get("status")) ? 10 : 2;
        });
        when(setmeals.countByMap(anyMap())).thenAnswer(call -> {
            Map<String, Object> query = call.getArgument(0);
            return Integer.valueOf(1).equals(query.get("status")) ? 4 : 1;
        });
        assertEquals(10, service.getDishOverView().getSold());
        assertEquals(2, service.getDishOverView().getDiscontinued());
        assertEquals(4, service.getSetmealOverView().getSold());
        assertEquals(1, service.getSetmealOverView().getDiscontinued());
    }
}
