package com.stocksentinel.strategy;

import com.stocksentinel.common.entity.StrategyDefinition;
import com.stocksentinel.common.entity.MarketDailyBar;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;
import com.stocksentinel.market.MarketContracts.Adjustment;

@Mapper
public interface StrategyMapper {
    class HistoryState {
        public long total;
        public OffsetDateTime lastFetched;
        public int sourceCount;
        public long invalidRows;
    }
    @Select("SELECT * FROM strategy_definition ORDER BY code") List<StrategyDefinition> list();
    @Select("SELECT * FROM strategy_definition WHERE code=#{code}") StrategyDefinition find(String code);
    @Select("SELECT * FROM strategy_definition WHERE code=#{code} FOR UPDATE") StrategyDefinition locked(String code);
    @Select("SELECT * FROM strategy_definition WHERE code=#{code} FOR SHARE") StrategyDefinition shared(String code);
    @Select("SELECT parameters::text FROM strategy_revision WHERE strategy_code=#{code} AND version=#{version}")
    String revision(@Param("code") String code,@Param("version") String version);
    @Select("SELECT jsonb_build_object('version',version,'parameters',parameters,'createdAt',created_at)::text FROM strategy_revision WHERE strategy_code=#{code} ORDER BY created_at,version")
    List<String> revisions(String code);
    @Insert("INSERT INTO strategy_revision(strategy_code,version,parameters) VALUES(#{code},#{version},CAST(#{parameters} AS jsonb)) ON CONFLICT(strategy_code,version) DO NOTHING")
    int addRevision(@Param("code") String code,@Param("version") String version,@Param("parameters") String parameters);
    @Update("UPDATE strategy_definition SET version=#{version},parameters=CAST(#{parameters} AS jsonb),enabled=#{enabled} WHERE code=#{code}")
    int update(@Param("code") String code,@Param("version") String version,@Param("parameters") String parameters,@Param("enabled") boolean enabled);
    @Select("SELECT count(*) AS total,max(fetched_at) AS last_fetched,count(DISTINCT source) AS source_count,count(*) FILTER (WHERE close_price<=0 OR open_price<=0 OR low_price<=0 OR high_price<=0 OR low_price>LEAST(open_price,close_price) OR high_price<GREATEST(open_price,close_price) OR volume<0) AS invalid_rows FROM market_daily_bar WHERE symbol=#{symbol} AND adjustment=#{adjustment} AND trade_date<=#{date}")
    HistoryState state(@Param("symbol") String symbol,@Param("adjustment") Adjustment adjustment,@Param("date") LocalDate date);
    @Select("SELECT * FROM market_daily_bar WHERE symbol=#{symbol} AND adjustment=#{adjustment} AND trade_date<=#{date} ORDER BY trade_date DESC LIMIT 6")
    List<MarketDailyBar> recent(@Param("symbol") String symbol,@Param("adjustment") Adjustment adjustment,@Param("date") LocalDate date);
    @Select("SELECT * FROM market_daily_bar WHERE symbol=#{symbol} AND adjustment='NONE' AND trade_date=#{date}")
    MarketDailyBar reference(@Param("symbol") String symbol,@Param("date") LocalDate date);
}