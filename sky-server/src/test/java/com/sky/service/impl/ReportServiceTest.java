package com.sky.service.impl;

import com.sky.dto.GoodsSalesDTO;
import com.sky.entity.Orders;
import com.sky.exception.BaseException;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.UserMapper;
import com.sky.service.WorkspaceService;
import com.sky.vo.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportServiceTest {
    private final LocalDate begin = LocalDate.of(2026, 10, 31);
    private final LocalDate end = begin.plusDays(1);
    private ReportServiceImpl service;
    private OrderMapper orders;
    private UserMapper users;
    private WorkspaceService workspace;

    @BeforeEach
    void setUp() {
        service = new ReportServiceImpl();
        orders = mock(OrderMapper.class);
        users = mock(UserMapper.class);
        workspace = mock(WorkspaceService.class);
        ReflectionTestUtils.setField(service, "orderMapper", orders);
        ReflectionTestUtils.setField(service, "userMapper", users);
        ReflectionTestUtils.setField(service, "workspaceService", workspace);
    }

    @Test
    void turnoverIncludesBothDaysAndFillsMissingRevenueWithZero() {
        List<Map<String, Object>> queries = new ArrayList<>();
        when(orders.sumByMap(anyMap())).thenAnswer(call -> {
            Map<String, Object> query = call.getArgument(0);
            queries.add(new HashMap<>(query));
            return queries.size() == 1 ? 12.5 : null;
        });
        TurnoverReportVO result = service.getTurnoverStatistics(begin, end);
        assertEquals("2026-10-31,2026-11-01", result.getDateList());
        assertEquals("12.5,0.0", result.getTurnoverList());
        assertEquals(begin.atStartOfDay(), queries.get(0).get("begin"));
        assertEquals(end.atStartOfDay(), queries.get(0).get("end"));
        assertEquals(end.plusDays(1).atStartOfDay(), queries.get(1).get("end"));
        assertTrue(queries.stream().allMatch(query -> Orders.COMPLETED.equals(query.get("status"))));
    }

    @Test
    void cumulativeUsersDoNotHaveLowerDateBound() {
        List<Map<String, Object>> queries = new ArrayList<>();
        when(users.countByMap(anyMap())).thenAnswer(call -> {
            Map<String, Object> query = call.getArgument(0);
            queries.add(new HashMap<>(query));
            return query.containsKey("begin") ? 2 : 20;
        });
        UserReportVO result = service.getUserStatistics(begin, end);
        assertEquals("2,2", result.getNewUserList());
        assertEquals("20,20", result.getTotalUserList());
        assertFalse(queries.get(1).containsKey("begin"));
        assertEquals(end.atStartOfDay(), queries.get(1).get("end"));
    }

    @Test
    void orderCompletionRateUsesTotalsRatherThanAverageOfDailyRates() {
        when(orders.countByMap(anyMap())).thenAnswer(call -> {
            Map<String, Object> query = call.getArgument(0);
            boolean first = begin.atStartOfDay().equals(query.get("begin"));
            return query.get("status") == null ? (first ? 10 : 2) : (first ? 5 : 2);
        });
        OrderReportVO result = service.getOrderStatistics(begin, end);
        assertEquals("10,2", result.getOrderCountList());
        assertEquals("5,2", result.getValidOrderCountList());
        assertEquals(12, result.getTotalOrderCount());
        assertEquals(7, result.getValidOrderCount());
        assertEquals(7.0 / 12, result.getOrderCompletionRate());
    }

    @Test
    void emptyStatisticsAndRankingAreUsableByCharts() {
        when(orders.countByMap(anyMap())).thenReturn(0);
        when(orders.getSalesTop10(any(), any())).thenReturn(Collections.emptyList());
        OrderReportVO result = service.getOrderStatistics(begin, begin);
        assertEquals("0", result.getOrderCountList());
        assertEquals(0.0, result.getOrderCompletionRate());
        assertEquals("", service.getSalesTop10(begin, begin).getNameList());
    }

    @Test
    void rankingPreservesNamesAndQuantitiesInSameOrder() {
        when(orders.getSalesTop10(begin.atStartOfDay(), end.plusDays(1).atStartOfDay()))
                .thenReturn(Arrays.asList(new GoodsSalesDTO("鱼香肉丝", 8), new GoodsSalesDTO("套餐", 3)));
        SalesTop10ReportVO result = service.getSalesTop10(begin, end);
        assertEquals("鱼香肉丝,套餐", result.getNameList());
        assertEquals("8,3", result.getNumberList());
    }

    @Test
    void invalidDatesFailBeforeAnyDatabaseQuery() {
        assertThrows(BaseException.class, () -> service.getTurnoverStatistics(null, end));
        assertThrows(BaseException.class, () -> service.getUserStatistics(begin, null));
        assertThrows(BaseException.class, () -> service.getOrderStatistics(end, begin));
        assertThrows(BaseException.class, () -> service.getSalesTop10(end, begin));
        verifyNoInteractions(orders, users);
    }

    @Test
    void exportedWorkbookHasSummaryAndThirtyCompleteDays() throws Exception {
        BusinessDataVO data = BusinessDataVO.builder().turnover(120.0).validOrderCount(3)
                .orderCompletionRate(0.75).unitPrice(40.0).newUsers(2).build();
        List<LocalDateTime> starts = new ArrayList<>();
        when(workspace.getBusinessData(any(), any())).thenAnswer(call -> {
            starts.add(call.getArgument(0));
            return data;
        });
        LocalDate today = LocalDate.now();
        MockHttpServletResponse response = new MockHttpServletResponse();
        service.exportBusinessData(response);
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", response.getContentType());
        assertTrue(response.getHeader("Content-Disposition").endsWith(".xlsx"));
        assertEquals(31, starts.size());
        assertEquals(today.minusDays(30).atStartOfDay(), starts.get(0));
        verify(workspace).getBusinessData(today.minusDays(30).atStartOfDay(), today.atStartOfDay());
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            org.apache.poi.xssf.usermodel.XSSFSheet sheet = workbook.getSheetAt(0);
            assertEquals(120.0, sheet.getRow(3).getCell(2).getNumericCellValue());
            assertEquals(0.75, sheet.getRow(3).getCell(4).getNumericCellValue());
            assertEquals(40.0, sheet.getRow(4).getCell(4).getNumericCellValue());
            for (int i = 0; i < 30; i++) {
                assertEquals(today.minusDays(30).plusDays(i).toString(), sheet.getRow(7 + i).getCell(1).getStringCellValue());
                assertEquals(120.0, sheet.getRow(7 + i).getCell(2).getNumericCellValue());
                assertEquals(2.0, sheet.getRow(7 + i).getCell(6).getNumericCellValue());
            }
            assertTrue(sheet.getNumMergedRegions() > 0);
        }
    }
}
