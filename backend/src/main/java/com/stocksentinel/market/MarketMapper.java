package com.stocksentinel.market;

import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.*;
import com.stocksentinel.common.entity.MarketDailyBar;
import com.stocksentinel.market.MarketContracts.*;

@Mapper
public interface MarketMapper {
    @Select("SELECT trade_date,source FROM market_daily_bar WHERE symbol=#{symbol} AND adjustment=#{adjustment}")
    List<MarketDailyBar> sourceHistory(@Param("symbol") String symbol,@Param("adjustment") Adjustment adjustment);
    @Select("SELECT count(*) FROM stock_info WHERE symbol=#{symbol}")
    int stockExists(String symbol);

    @Insert("""
        <script>
        INSERT INTO stock_info(symbol,name,exchange) VALUES
        <foreach collection="stocks" item="s" separator=",">(#{s.symbol},#{s.name},#{s.exchange})</foreach>
        ON CONFLICT(symbol) DO UPDATE SET name=EXCLUDED.name,exchange=EXCLUDED.exchange,updated_at=now()
        </script>
        """)
    int upsertStocks(@Param("stocks") List<Stock> stocks);

    @Insert("""
        <script>
        INSERT INTO market_daily_bar(symbol,trade_date,open_price,high_price,low_price,close_price,
            volume,amount,turnover_rate,adjustment,source) VALUES
        <foreach collection="bars" item="b" separator=",">
          (#{b.symbol},#{b.tradeDate},#{b.openPrice},#{b.highPrice},#{b.lowPrice},#{b.closePrice},
           #{b.volume},#{b.amount},#{b.turnoverRate},#{b.adjustment},#{b.source})
        </foreach>
        ON CONFLICT(symbol,trade_date,adjustment) DO UPDATE SET
          open_price=EXCLUDED.open_price,high_price=EXCLUDED.high_price,low_price=EXCLUDED.low_price,
          close_price=EXCLUDED.close_price,volume=EXCLUDED.volume,amount=EXCLUDED.amount,
          turnover_rate=EXCLUDED.turnover_rate,source=EXCLUDED.source,fetched_at=now()
        </script>
        """)
    int upsertBars(@Param("bars") List<Bar> bars);

    @Select("SELECT * FROM market_daily_bar WHERE symbol=#{symbol} AND adjustment=#{adjustment} AND trade_date BETWEEN #{start} AND #{end} ORDER BY trade_date")
    List<MarketDailyBar> daily(@Param("symbol") String symbol, @Param("start") LocalDate start,
                             @Param("end") LocalDate end, @Param("adjustment") Adjustment adjustment);
}
