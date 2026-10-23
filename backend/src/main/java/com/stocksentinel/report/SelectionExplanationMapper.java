package com.stocksentinel.report;

import java.time.*;
import java.math.BigDecimal;
import org.apache.ibatis.annotations.*;
import com.stocksentinel.pullback.PullbackMapper;

@Mapper
public interface SelectionExplanationMapper {
    class Item {public String status,code,details;public BigDecimal averageAmount;}
    @Select("SELECT status,code,details::text AS details,average_amount FROM market_screen_item WHERE trade_date=#{date} AND symbol=#{symbol}")
    Item item(@Param("date") LocalDate date,@Param("symbol") String symbol);
    @Select("SELECT * FROM pullback_candidate WHERE symbol=#{symbol} AND data_date=#{date} AND version=#{version}")
    PullbackMapper.Candidate candidate(@Param("symbol") String symbol,@Param("date") LocalDate date,@Param("version") String version);
    @Select("""
        SELECT position FROM (
          SELECT p.symbol,row_number() OVER(ORDER BY p.average_amount DESC,p.symbol) AS position
          FROM pullback_candidate p JOIN stock_info s USING(symbol)
          WHERE p.data_date=#{date} AND p.version=#{version} AND p.triggered_at IS NULL
            AND s.status='ACTIVE' AND s.exchange IN ('SH','SZ') AND upper(s.name) NOT LIKE '%ST%'
        ) ranked WHERE symbol=#{symbol}
        """)
    Long rank(@Param("symbol") String symbol,@Param("date") LocalDate date,@Param("version") String version);
    @Select("""
        SELECT position FROM (
          SELECT symbol,row_number() OVER(ORDER BY average_amount DESC,symbol) AS position
          FROM market_screen_item WHERE trade_date=#{date} AND status='CANDIDATE'
        ) ranked WHERE symbol=#{symbol}
        """)
    Long swingRank(@Param("symbol") String symbol,@Param("date") LocalDate date);
}
