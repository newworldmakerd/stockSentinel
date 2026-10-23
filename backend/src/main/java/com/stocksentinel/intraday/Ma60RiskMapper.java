package com.stocksentinel.intraday;

import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface Ma60RiskMapper {
    class Basis {
        public LocalDate referenceDate;public String source,referenceSource;
        public BigDecimal ma60,qfqClose,referenceClose;public Long historyCount,sourceCount;
        public OffsetDateTime calculatedAt,lastFetched;
    }
    class Quote {public BigDecimal previousClose;public OffsetDateTime quotedAt;}
    class State {
        public String symbol,baselineKey,baseline,status,code;public LocalDate tradeDate,referenceDate;
        public BigDecimal threshold;public OffsetDateTime lastBarAt,lastTriggerAt,checkedAt;
    }
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{symbol},763017))") Object lockSymbol(String symbol);
    @Select("""
        SELECT q.trade_date AS reference_date,q.source,n.source AS reference_source,q.close_price AS qfq_close,
          n.close_price AS reference_close,i.ma60,i.calculated_at,
          (SELECT max(fetched_at) FROM market_daily_bar b WHERE b.symbol=q.symbol AND b.adjustment='QFQ' AND b.trade_date<=q.trade_date) AS last_fetched,
          (SELECT count(*) FROM market_daily_bar b WHERE b.symbol=q.symbol AND b.adjustment='QFQ' AND b.trade_date<=q.trade_date) AS history_count,
          (SELECT count(DISTINCT source) FROM market_daily_bar b WHERE b.symbol=q.symbol AND b.adjustment='QFQ' AND b.trade_date<=q.trade_date) AS source_count
        FROM market_daily_bar q
        JOIN market_daily_bar n ON n.symbol=q.symbol AND n.trade_date=q.trade_date AND n.adjustment='NONE'
        JOIN market_indicator_daily i ON i.symbol=q.symbol AND i.trade_date=q.trade_date AND i.adjustment='QFQ' AND i.calculation_version='ma-macd-vol-v1'
        WHERE q.symbol=#{symbol} AND q.trade_date=#{date} AND q.adjustment='QFQ'
        """) Basis basis(@Param("symbol") String symbol,@Param("date") LocalDate date);
    @Select("""
        SELECT (q->>'previous_close')::numeric AS previous_close,(q->>'quoted_at')::timestamptz AS quoted_at
        FROM market_screen_run r CROSS JOIN LATERAL jsonb_array_elements(r.overview::jsonb->'quotes') q
        JOIN stock_info s ON q->>'key'=(CASE WHEN s.exchange='SH' THEN 'sh' ELSE 'sz' END)||s.symbol
        WHERE r.trade_date=#{date} AND s.symbol=#{symbol} AND r.overview::jsonb->>'source'='TENCENT_QUOTE'
        LIMIT 1
        """) Quote quote(@Param("symbol") String symbol,@Param("date") LocalDate date);
    @Select("SELECT symbol,trade_date,reference_date,baseline_key,threshold,baseline::text AS baseline,last_bar_at,last_trigger_at,status,code,checked_at FROM intraday_ma60_state WHERE symbol=#{symbol} FOR UPDATE") State lock(String symbol);
    @Insert("""
        INSERT INTO intraday_ma60_state(symbol,trade_date,reference_date,baseline_key,threshold,baseline,last_bar_at,last_trigger_at,status,code)
        VALUES(#{symbol},#{tradeDate},#{referenceDate},#{baselineKey},#{threshold},CAST(#{baseline} AS jsonb),#{lastBarAt},#{lastTriggerAt},#{status},#{code})
        ON CONFLICT(symbol) DO UPDATE SET trade_date=EXCLUDED.trade_date,reference_date=EXCLUDED.reference_date,
          baseline_key=EXCLUDED.baseline_key,threshold=EXCLUDED.threshold,baseline=EXCLUDED.baseline,
          last_bar_at=EXCLUDED.last_bar_at,last_trigger_at=EXCLUDED.last_trigger_at,status=EXCLUDED.status,code=EXCLUDED.code,checked_at=now()
        """) int save(State state);
    @Select("""
        SELECT m.symbol,s.name,coalesce(r.status,'PENDING') AS status,coalesce(r.code,'NOT_CHECKED_TODAY') AS code,
          r.reference_date,r.threshold,r.last_bar_at,r.last_trigger_at,r.checked_at
        FROM intraday_monitor m JOIN stock_watchlist w ON w.symbol=m.symbol JOIN stock_info s ON s.symbol=m.symbol
        LEFT JOIN intraday_ma60_state r ON r.symbol=m.symbol AND r.trade_date=#{date}
        WHERE m.enabled AND w.enabled AND s.exchange IN ('SH','SZ') ORDER BY m.symbol
        """) List<Map<String,Object>> status(LocalDate date);
}
