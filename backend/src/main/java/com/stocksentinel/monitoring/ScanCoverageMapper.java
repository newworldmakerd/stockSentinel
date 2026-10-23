package com.stocksentinel.monitoring;

import java.time.*;import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ScanCoverageMapper {
    class Quote {
        public String key,outcome,code;public boolean dataValid;public OffsetDateTime quoteAt;
        public Quote(){}
        public Quote(String key,String outcome,String code,boolean valid,OffsetDateTime at){this.key=key;this.outcome=outcome;this.code=code;this.dataValid=valid;this.quoteAt=at;}
    }
    @Insert("""
        <script>INSERT INTO scan_quote_state(trade_date,phase,quote_key,cycle_id,outcome,code,data_valid,last_attempt_at,last_success_at,last_quote_at,consecutive_failures) VALUES
        <foreach collection="items" item="q" separator=",">(#{date},#{phase},#{q.key},#{cycle},#{q.outcome},#{q.code},#{q.dataValid},#{now},CASE WHEN #{q.dataValid} THEN #{now}::timestamptz ELSE NULL END,#{q.quoteAt},CASE WHEN #{q.outcome}='FAILED' THEN 1 ELSE 0 END)</foreach>
        ON CONFLICT(trade_date,phase,quote_key) DO UPDATE SET cycle_id=EXCLUDED.cycle_id,outcome=EXCLUDED.outcome,code=EXCLUDED.code,data_valid=EXCLUDED.data_valid,
        attempts=scan_quote_state.attempts+1,last_attempt_at=EXCLUDED.last_attempt_at,
        last_success_at=COALESCE(EXCLUDED.last_success_at,scan_quote_state.last_success_at),last_quote_at=COALESCE(EXCLUDED.last_quote_at,scan_quote_state.last_quote_at),
        consecutive_failures=CASE WHEN EXCLUDED.outcome='FAILED' THEN scan_quote_state.consecutive_failures+1 ELSE 0 END</script>
        """)
    int record(@Param("date") LocalDate date,@Param("phase") String phase,@Param("cycle") String cycle,@Param("now") OffsetDateTime now,@Param("items") List<Quote> items);
    @Select("SELECT quote_key FROM scan_quote_state WHERE trade_date=#{date} AND phase=#{phase} AND cycle_id=#{cycle}")
    Set<String> covered(@Param("date")LocalDate date,@Param("phase")String phase,@Param("cycle")String cycle);
    @Select("SELECT count(*) AS attempted,count(*) FILTER(WHERE data_valid) AS valid,count(*) FILTER(WHERE outcome='FAILED') AS failed,count(*) FILTER(WHERE outcome='SKIPPED') AS skipped,count(*) FILTER(WHERE outcome='CHECKED') AS evaluated FROM scan_quote_state WHERE trade_date=#{date} AND phase=#{phase} AND cycle_id=#{cycle}")
    Map<String,Object> cycleCounts(@Param("date")LocalDate date,@Param("phase")String phase,@Param("cycle")String cycle);
    @Select("SELECT phase,outcome,code,count(*) FROM scan_quote_state WHERE trade_date=#{date} GROUP BY phase,outcome,code ORDER BY phase,outcome,code")
    List<Map<String,Object>> quoteSummary(LocalDate date);
    @Select("SELECT phase,outcome,code,data_valid,attempts,consecutive_failures,last_attempt_at,last_success_at,last_quote_at FROM scan_quote_state WHERE trade_date=#{date} AND quote_key=#{key} ORDER BY phase")
    List<Map<String,Object>> stockQuotes(@Param("date")LocalDate date,@Param("key")String key);
    @Select("SELECT as_of,status,code,attempts,next_retry_at,last_attempt_at,last_success_at,last_error_code,updated_at FROM trend_follow_item WHERE as_of=#{date} AND symbol=#{symbol}")
    Map<String,Object> trendHistory(@Param("date")LocalDate date,@Param("symbol")String symbol);
    @Select("SELECT trade_date,status,code,attempts,last_attempt_at,last_success_at,last_error_code FROM market_screen_item WHERE trade_date=#{date} AND symbol=#{symbol}")
    Map<String,Object> screening(@Param("date")LocalDate date,@Param("symbol")String symbol);
    @Select("SELECT symbol,status,code,attempts,next_retry_at,last_attempt_at,last_success_at,last_error_code FROM trend_follow_item WHERE as_of=#{date} AND status='FAILED' ORDER BY symbol LIMIT #{limit} OFFSET #{offset}")
    List<Map<String,Object>> historyFailures(@Param("date")LocalDate date,@Param("limit")int limit,@Param("offset")int offset);
    @Select("SELECT symbol,status,code,attempts,last_attempt_at,last_success_at,last_error_code FROM market_screen_item WHERE trade_date=#{date} AND status='FAILED' ORDER BY symbol LIMIT #{limit} OFFSET #{offset}")
    List<Map<String,Object>> screeningFailures(@Param("date")LocalDate date,@Param("limit")int limit,@Param("offset")int offset);
    @Select("SELECT phase,quote_key,outcome,code,attempts,consecutive_failures,last_attempt_at,last_success_at,last_quote_at FROM scan_quote_state WHERE trade_date=#{date} AND outcome='FAILED' ORDER BY phase,quote_key LIMIT #{limit} OFFSET #{offset}")
    List<Map<String,Object>> quoteFailures(@Param("date")LocalDate date,@Param("limit")int limit,@Param("offset")int offset);
}
