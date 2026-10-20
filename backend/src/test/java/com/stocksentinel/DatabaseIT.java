package com.stocksentinel;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class DatabaseIT {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:18-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Test void migrationCreatesCoreTables() {
        assertEquals(10, jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN ('stock_info','stock_watchlist','market_daily_bar','market_indicator_daily','strategy_definition','signal_event','signal_rule_hit','notify_record','job_execution','market_calendar')", Integer.class));
        assertEquals(9, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class));
    }
    @Test void dailyBarsAreUnique() {
        jdbc.update("INSERT INTO stock_info(symbol,name,exchange) VALUES ('600487','test','SH')");
        String insert = "INSERT INTO market_daily_bar(symbol,trade_date,open_price,high_price,low_price,close_price,volume,source) VALUES ('600487','2026-10-20',1,1,1,1,100,'test')";
        jdbc.update(insert);
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> jdbc.update(insert));
    }
}
