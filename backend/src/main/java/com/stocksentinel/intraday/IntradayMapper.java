package com.stocksentinel.intraday;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.time.*;
import com.stocksentinel.intraday.IntradayContracts.*;
import com.stocksentinel.common.entity.SignalEvent;
import com.stocksentinel.jobs.JobMapper;

@Mapper
public interface IntradayMapper {
    @Select("SELECT * FROM intraday_monitor WHERE symbol=#{symbol}") Config config(String symbol);
    @Select("SELECT * FROM intraday_monitor WHERE symbol=#{symbol} FOR UPDATE") Config lock(String symbol);
    @Select("SELECT enabled FROM stock_watchlist WHERE symbol=#{symbol} FOR SHARE") Boolean lockWatch(String symbol);
    @Select("SELECT m.* FROM intraday_monitor m JOIN stock_watchlist w ON w.symbol=m.symbol JOIN stock_info s ON s.symbol=m.symbol WHERE m.enabled AND w.enabled AND s.exchange IN ('SH','SZ') ORDER BY m.symbol")
    List<Config> monitors();
    @Select("SELECT * FROM intraday_monitor ORDER BY symbol") List<Config> all();
    @Insert("""
        INSERT INTO intraday_monitor(symbol,enabled,rapid_pct,volume_multiplier,cooldown_minutes,take_profit,stop_loss)
        VALUES(#{symbol},#{p.enabled},#{p.rapidPct},#{p.volumeMultiplier},#{p.cooldownMinutes},#{p.takeProfit},#{p.stopLoss})
        ON CONFLICT(symbol) DO UPDATE SET enabled=EXCLUDED.enabled,rapid_pct=EXCLUDED.rapid_pct,
        volume_multiplier=EXCLUDED.volume_multiplier,cooldown_minutes=EXCLUDED.cooldown_minutes,
        take_profit=EXCLUDED.take_profit,stop_loss=EXCLUDED.stop_loss,revision=intraday_monitor.revision+1,updated_at=now()
        WHERE (intraday_monitor.enabled,intraday_monitor.rapid_pct,intraday_monitor.volume_multiplier,intraday_monitor.cooldown_minutes,intraday_monitor.take_profit,intraday_monitor.stop_loss)
        IS DISTINCT FROM (EXCLUDED.enabled,EXCLUDED.rapid_pct,EXCLUDED.volume_multiplier,EXCLUDED.cooldown_minutes,EXCLUDED.take_profit,EXCLUDED.stop_loss)
        """)
    int configure(@Param("symbol") String symbol,@Param("p") Parameters parameters);
    @Select("SELECT * FROM intraday_cursor WHERE symbol=#{symbol}") Cursor cursor(String symbol);
    @Insert("INSERT INTO intraday_cursor(symbol,last_bar_at,config_revision) VALUES(#{symbol},#{at},#{revision}) ON CONFLICT(symbol) DO UPDATE SET last_bar_at=EXCLUDED.last_bar_at,config_revision=EXCLUDED.config_revision")
    int updateCursor(@Param("symbol") String symbol,@Param("at") OffsetDateTime at,@Param("revision") long revision);
    @Select("SELECT * FROM intraday_alert_state WHERE symbol=#{symbol} AND signal_type=#{type}")
    State state(@Param("symbol") String symbol,@Param("type") String type);
    @Insert("""
        INSERT INTO intraday_alert_state(symbol,signal_type,active,config_revision,last_trigger_at) VALUES(#{symbol},#{type},#{active},#{revision},#{at})
        ON CONFLICT(symbol,signal_type) DO UPDATE SET active=EXCLUDED.active,config_revision=EXCLUDED.config_revision,last_trigger_at=EXCLUDED.last_trigger_at
        """)
    int updateState(@Param("symbol") String symbol,@Param("type") String type,@Param("active") boolean active,@Param("revision") long revision,@Param("at") OffsetDateTime at);
    @Insert("""
        INSERT INTO market_minute_bar(symbol,bar_at,open_price,high_price,low_price,close_price,volume,amount,source,fetched_at)
        VALUES(#{symbol},#{b.barAt},#{b.openPrice},#{b.highPrice},#{b.lowPrice},#{b.closePrice},#{b.volume},#{b.amount},#{source},#{fetched})
        ON CONFLICT(symbol,bar_at) DO UPDATE SET open_price=EXCLUDED.open_price,high_price=EXCLUDED.high_price,
        low_price=EXCLUDED.low_price,close_price=EXCLUDED.close_price,volume=EXCLUDED.volume,amount=EXCLUDED.amount,source=EXCLUDED.source,fetched_at=EXCLUDED.fetched_at
        """)
    int bar(@Param("symbol") String symbol,@Param("b") Bar bar,@Param("source") String source,@Param("fetched") OffsetDateTime fetched);
    @Insert("INSERT INTO signal_event(symbol,trade_date,strategy_code,strategy_version,signal_type,reference_price,reason,risk_note,details,signal_at) VALUES(#{symbol},#{tradeDate},#{strategyCode},#{strategyVersion},#{signalType},#{referencePrice},#{reason},#{riskNote},CAST(#{details} AS jsonb),#{signalAt}) ON CONFLICT(symbol,strategy_code,strategy_version,signal_type,signal_at) WHERE signal_at IS NOT NULL DO NOTHING")
    @Options(useGeneratedKeys=true,keyProperty="id",keyColumn="id")
    int signal(SignalEvent event);
    @Insert("INSERT INTO job_execution(job_name,status,trade_date,trigger_type) VALUES('INTRADAY_SCAN','RUNNING',#{date},#{trigger})")
    @Options(useGeneratedKeys=true,keyProperty="id",keyColumn="id") int start(JobMapper.Execution job);
    @Update("UPDATE job_execution SET status='FAILED',finished_at=now(),error_message='PREVIOUS_PROCESS_INTERRUPTED' WHERE job_name='INTRADAY_SCAN' AND status='RUNNING' AND id!=#{id}")
    int recover(long id);
}
