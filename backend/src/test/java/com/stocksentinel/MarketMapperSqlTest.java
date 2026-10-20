package com.stocksentinel;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.stocksentinel.market.MarketMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarketMapperSqlTest {
    @Test void annotatedBatchStatementsParseWithoutDatabase() {
        var config=new MybatisConfiguration();
        config.addMapper(MarketMapper.class);
        String stock=config.getMappedStatement(MarketMapper.class.getName()+".upsertStocks")
            .getBoundSql(Map.of("stocks",List.of(new com.stocksentinel.market.MarketContracts.Stock("600487","test","SH")))).getSql();
        assertTrue(stock.contains("ON CONFLICT(symbol)"));
        assertFalse(stock.contains("status="));
        assertTrue(config.hasStatement(MarketMapper.class.getName()+".upsertBars"));
    }
}