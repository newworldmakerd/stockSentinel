package com.stocksentinel;

import com.stocksentinel.market.MarketWriter;
import com.stocksentinel.market.MarketContracts.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in: dedicated local test database only. Flyway creates schema; test rows roll back. */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named="STOCKSENTINEL_TEST_DB_URL", matches="jdbc:postgresql:.*")
class LocalDatabaseIT {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->System.getenv("STOCKSENTINEL_TEST_DB_URL"));
        registry.add("spring.datasource.username",()->System.getenv("STOCKSENTINEL_TEST_DB_USER"));
        registry.add("spring.datasource.password",()->System.getenv("STOCKSENTINEL_TEST_DB_PASSWORD"));
        registry.add("market.enabled",()->false);
        registry.add("llm.enabled",()->false);
    }
    @org.junit.jupiter.api.BeforeEach void guardFixtureSymbol() {
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM stock_info WHERE symbol='999999'",Integer.class),
            "Fixture symbol already exists; refusing to modify existing rows");
    }
    @Autowired MarketWriter writer;
    @Autowired JdbcTemplate jdbc;
    private Bar bar(LocalDate day, Adjustment adjustment, String close) {
        return new Bar("999999",day,new BigDecimal("10"),new BigDecimal("12"),new BigDecimal("9"),
            new BigDecimal(close),new BigDecimal("12300"),new BigDecimal("135300"),new BigDecimal("0.0125"),
            adjustment,"fixture",true);
    }
    @Test void importsMoreThan120BarsAndIsIdempotentAcrossAdjustments() {
        writer.stocks(List.of(new Stock("999999","fixture","SH")));
        var bars=IntStream.range(0,130).mapToObj(i->bar(LocalDate.of(2025,1,1).plusDays(i),Adjustment.QFQ,"11")).toList();
        assertEquals(130,writer.bars(bars));
        assertEquals(130,writer.bars(bars));
        assertEquals(130,jdbc.queryForObject("SELECT count(*) FROM market_daily_bar WHERE symbol='999999' AND adjustment='QFQ'",Integer.class));
        writer.bars(List.of(bar(LocalDate.of(2025,1,1),Adjustment.NONE,"10.5")));
        assertEquals(131,jdbc.queryForObject("SELECT count(*) FROM market_daily_bar WHERE symbol='999999'",Integer.class));
        writer.bars(List.of(bar(LocalDate.of(2025,1,1),Adjustment.QFQ,"11.5")));
        assertEquals(0,new BigDecimal("11.5").compareTo(jdbc.queryForObject("SELECT close_price FROM market_daily_bar WHERE symbol='999999' AND adjustment='QFQ' AND trade_date='2025-01-01'",BigDecimal.class)));
        assertEquals(9,jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success",Integer.class));
    }
    @Test void stockSyncPreservesBusinessStatusAndOptionalMetadata() {
        jdbc.update("INSERT INTO stock_info(symbol,name,exchange,industry,status) VALUES ('999999','old','SH','fixture-industry','SUSPENDED')");
        writer.stocks(List.of(new Stock("999999","new","SH")));
        assertEquals("SUSPENDED",jdbc.queryForObject("SELECT status FROM stock_info WHERE symbol='999999'",String.class));
        assertEquals("fixture-industry",jdbc.queryForObject("SELECT industry FROM stock_info WHERE symbol='999999'",String.class));
        assertEquals("new",jdbc.queryForObject("SELECT name FROM stock_info WHERE symbol='999999'",String.class));
    }
}
