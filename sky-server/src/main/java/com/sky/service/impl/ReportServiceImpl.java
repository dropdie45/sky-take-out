package com.sky.service.impl;

import com.sky.dto.GoodsSalesDTO;
import com.sky.entity.Orders;
import com.sky.exception.BaseException;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.UserMapper;
import com.sky.service.ReportService;
import com.sky.service.WorkspaceService;
import com.sky.vo.*;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ReportServiceImpl implements ReportService {
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private WorkspaceService workspaceService;

    private List<LocalDate> dates(LocalDate begin, LocalDate end) {
        if (begin == null || end == null || begin.isAfter(end) || LocalDate.MAX.equals(end)) {
            throw new BaseException("请提供有效的统计起止日期，开始日期不能晚于结束日期");
        }
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate date = begin; !date.isAfter(end); date = date.plusDays(1)) {
            dates.add(date);
        }
        return dates;
    }

    private Map<String, Object> conditions(LocalDate begin, LocalDate end) {
        Map<String, Object> conditions = new HashMap<>();
        conditions.put("begin", begin.atStartOfDay());
        conditions.put("end", end.plusDays(1).atStartOfDay());
        return conditions;
    }

    private String join(List<?> values) {
        return values.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    @Override
    public TurnoverReportVO getTurnoverStatistics(LocalDate begin, LocalDate end) {
        List<LocalDate> dates = dates(begin, end);
        List<Double> turnover = new ArrayList<>();
        for (LocalDate date : dates) {
            Map<String, Object> conditions = conditions(date, date);
            conditions.put("status", Orders.COMPLETED);
            Double value = orderMapper.sumByMap(conditions);
            turnover.add(value == null ? 0.0 : value);
        }
        return TurnoverReportVO.builder().dateList(join(dates)).turnoverList(join(turnover)).build();
    }

    @Override
    public UserReportVO getUserStatistics(LocalDate begin, LocalDate end) {
        List<LocalDate> dates = dates(begin, end);
        List<Integer> newUsers = new ArrayList<>();
        List<Integer> totalUsers = new ArrayList<>();
        for (LocalDate date : dates) {
            Map<String, Object> conditions = conditions(date, date);
            newUsers.add(userMapper.countByMap(conditions));
            conditions = new HashMap<>();
            conditions.put("end", date.plusDays(1).atStartOfDay());
            totalUsers.add(userMapper.countByMap(conditions));
        }
        return UserReportVO.builder().dateList(join(dates)).newUserList(join(newUsers))
                .totalUserList(join(totalUsers)).build();
    }

    @Override
    public OrderReportVO getOrderStatistics(LocalDate begin, LocalDate end) {
        List<LocalDate> dates = dates(begin, end);
        List<Integer> orders = new ArrayList<>();
        List<Integer> validOrders = new ArrayList<>();
        for (LocalDate date : dates) {
            Map<String, Object> conditions = conditions(date, date);
            orders.add(orderMapper.countByMap(conditions));
            conditions = new HashMap<>(conditions);
            conditions.put("status", Orders.COMPLETED);
            validOrders.add(orderMapper.countByMap(conditions));
        }
        int total = orders.stream().mapToInt(Integer::intValue).sum();
        int valid = validOrders.stream().mapToInt(Integer::intValue).sum();
        return OrderReportVO.builder().dateList(join(dates)).orderCountList(join(orders))
                .validOrderCountList(join(validOrders)).totalOrderCount(total).validOrderCount(valid)
                .orderCompletionRate(total == 0 ? 0.0 : (double) valid / total).build();
    }

    @Override
    public SalesTop10ReportVO getSalesTop10(LocalDate begin, LocalDate end) {
        dates(begin, end);
        List<GoodsSalesDTO> sales = orderMapper.getSalesTop10(begin.atStartOfDay(), end.plusDays(1).atStartOfDay());
        return SalesTop10ReportVO.builder()
                .nameList(join(sales.stream().map(GoodsSalesDTO::getName).collect(Collectors.toList())))
                .numberList(join(sales.stream().map(GoodsSalesDTO::getNumber).collect(Collectors.toList())))
                .build();
    }

    @Override
    public void exportBusinessData(HttpServletResponse response) {
        LocalDate end = LocalDate.now().minusDays(1);
        LocalDate begin = end.minusDays(29);
        try (InputStream template = getClass().getResourceAsStream("/template/运营数据报表模板.xlsx")) {
            if (template == null) {
                throw new BaseException("运营数据报表模板不存在");
            }
            try (XSSFWorkbook workbook = new XSSFWorkbook(template)) {
                XSSFSheet sheet = workbook.getSheetAt(0);
                sheet.getRow(1).getCell(1).setCellValue("时间：" + begin + "至" + end);
                BusinessDataVO overview = workspaceService.getBusinessData(begin.atStartOfDay(), end.plusDays(1).atStartOfDay());
                sheet.getRow(3).getCell(2).setCellValue(overview.getTurnover());
                sheet.getRow(3).getCell(4).setCellValue(overview.getOrderCompletionRate());
                sheet.getRow(3).getCell(6).setCellValue(overview.getNewUsers());
                sheet.getRow(4).getCell(2).setCellValue(overview.getValidOrderCount());
                sheet.getRow(4).getCell(4).setCellValue(overview.getUnitPrice());
                for (int i = 0; i < 30; i++) {
                    LocalDate date = begin.plusDays(i);
                    BusinessDataVO data = workspaceService.getBusinessData(date.atStartOfDay(), date.plusDays(1).atStartOfDay());
                    XSSFRow row = sheet.getRow(7 + i);
                    row.getCell(1).setCellValue(date.toString());
                    row.getCell(2).setCellValue(data.getTurnover());
                    row.getCell(3).setCellValue(data.getValidOrderCount());
                    row.getCell(4).setCellValue(data.getOrderCompletionRate());
                    row.getCell(5).setCellValue(data.getUnitPrice());
                    row.getCell(6).setCellValue(data.getNewUsers());
                }
                response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                response.setHeader("Content-Disposition", "attachment; filename=business-report-" + end + ".xlsx");
                workbook.write(response.getOutputStream());
            }
        } catch (IOException e) {
            throw new IllegalStateException("运营数据报表导出失败", e);
        }
    }
}
