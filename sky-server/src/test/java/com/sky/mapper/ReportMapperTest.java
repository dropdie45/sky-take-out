package com.sky.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReportMapperTest {
    @Test
    void allMapperXmlFilesParseAndStatisticsBindDatesAndStatus() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAliases("com.sky.entity");
        configuration.getTypeAliasRegistry().registerAliases("com.sky.vo");
        configuration.getTypeAliasRegistry().registerAliases("com.sky.dto");
        for (String file : Arrays.asList("OederMapper.xml", "UserMapper.xml", "DishMapper.xml", "SetmealMapper.xml")) {
            String path = "mapper/" + file;
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
                assertNotNull(input);
                new XMLMapperBuilder(input, configuration, path, configuration.getSqlFragments()).parse();
            }
        }
        Map<String, Object> query = new HashMap<>();
        query.put("begin", LocalDate.of(2026, 11, 1).atStartOfDay());
        query.put("end", LocalDate.of(2026, 11, 2).atStartOfDay());
        query.put("status", 5);
        for (String name : Arrays.asList("OrderMapper.countByMap", "OrderMapper.sumByMap", "UserMapper.countByMap")) {
            BoundSql bound = configuration.getMappedStatement("com.sky.mapper." + name).getBoundSql(query);
            String sql = bound.getSql().replaceAll("\\s+", " ");
            assertTrue(sql.contains(">= ?"));
            assertTrue(sql.contains("< ?"));
            bound.getParameterMappings().forEach(parameter -> assertNotNull(query.get(parameter.getProperty())));
        }
        String top = configuration.getMappedStatement("com.sky.mapper.OrderMapper.getSalesTop10")
                .getBoundSql(query).getSql().replaceAll("\\s+", " ");
        assertTrue(top.contains("o.status = 5"));
        assertTrue(top.contains("group by od.name"));
        assertTrue(top.contains("order by number desc, od.name asc"));
        assertTrue(top.contains("limit 10"));
        for (String name : Arrays.asList("DishMapper", "SetmealMapper")) {
            assertEquals(1, configuration.getMappedStatement("com.sky.mapper." + name + ".countByMap")
                    .getBoundSql(query).getParameterMappings().size());
        }
    }
}
