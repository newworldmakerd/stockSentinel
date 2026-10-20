package com.stocksentinel.report;

import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import com.stocksentinel.common.entity.StockInfo;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ReportMapper {
    class Run {public LocalDate tradeDate,asOf;public String status,overview;public Integer total;}
    class Delivery {public Long id;public LocalDate tradeDate;public String payload,status,claimToken;public Integer attempts;}
    @Select("SELECT * FROM stock_info WHERE exchange IN ('SH','SZ') AND status='ACTIVE' ORDER BY symbol") List<StockInfo> universe();
    @Select("SELECT max(trade_date) FROM market_calendar WHERE is_open AND exchange='CN' AND trade_date<#{date}") LocalDate previousOpen(LocalDate date);
    @Insert("INSERT INTO market_screen_run(trade_date,as_of,total) VALUES(#{date},#{asOf},#{total}) ON CONFLICT DO NOTHING")
    int start(@Param("date") LocalDate date,@Param("asOf") LocalDate asOf,@Param("total") int total);
    @Select("SELECT trade_date,as_of,status,total,overview::text AS overview FROM market_screen_run WHERE trade_date=#{date}") Run run(LocalDate date);
    @Insert("INSERT INTO market_screen_item(trade_date,symbol) SELECT #{date},symbol FROM stock_info WHERE exchange IN ('SH','SZ') AND status='ACTIVE' ON CONFLICT DO NOTHING") int seed(LocalDate date);
    @Select("SELECT s.* FROM stock_info s JOIN market_screen_item i ON i.symbol=s.symbol WHERE i.trade_date=#{date} AND i.status='PENDING' ORDER BY EXISTS(SELECT 1 FROM market_daily_bar b WHERE b.symbol=s.symbol) DESC,s.symbol") List<StockInfo> pending(LocalDate date);
    @Update("UPDATE market_screen_run SET overview=CAST(#{json} AS jsonb) WHERE trade_date=#{date}") int overview(@Param("date") LocalDate date,@Param("json") String json);
    @Update("UPDATE market_screen_run SET status=#{status},finished_at=now() WHERE trade_date=#{date}") int finishRun(@Param("date") LocalDate date,@Param("status") String status);
    @Update("UPDATE market_screen_item SET status=#{status},code=#{code},average_amount=#{amount},details=CAST(#{details} AS jsonb) WHERE trade_date=#{date} AND symbol=#{symbol}")
    int item(@Param("date") LocalDate date,@Param("symbol") String symbol,@Param("status") String status,@Param("code") String code,@Param("amount") BigDecimal amount,@Param("details") String details);
    @Update("UPDATE market_screen_item SET explanation=#{text} WHERE trade_date=#{date} AND symbol=#{symbol} AND status='CANDIDATE'") int explanation(@Param("date") LocalDate date,@Param("symbol") String symbol,@Param("text") String text);
    @Select("SELECT i.symbol,s.name,i.average_amount,i.details::text AS details,i.explanation FROM market_screen_item i JOIN stock_info s ON s.symbol=i.symbol WHERE i.trade_date=#{date} AND i.status='CANDIDATE' ORDER BY average_amount DESC,i.symbol LIMIT 5") List<Map<String,Object>> candidates(LocalDate date);
    @Select("SELECT status,count(*) AS count FROM market_screen_item WHERE trade_date=#{date} GROUP BY status ORDER BY status") List<Map<String,Object>> coverage(LocalDate date);
    @Select("SELECT CASE WHEN count(*)=20 AND count(amount)=20 THEN avg(amount) ELSE NULL END FROM (SELECT amount FROM market_daily_bar WHERE symbol=#{symbol} AND adjustment='NONE' AND trade_date<=#{date} ORDER BY trade_date DESC LIMIT 20) x")
    BigDecimal average(@Param("symbol") String symbol,@Param("date") LocalDate date);
    @Select("SELECT count(*) FROM market_daily_bar WHERE symbol=#{symbol} AND adjustment='NONE' AND trade_date<=#{date}") int history(@Param("symbol") String symbol,@Param("date") LocalDate date);
    @Select("SELECT signal_type,count(*) AS count FROM signal_event WHERE trade_date=#{date} GROUP BY signal_type ORDER BY signal_type") List<Map<String,Object>> signals(LocalDate date);
    @Select("SELECT status,count(*) AS count FROM job_execution WHERE trade_date=#{date} AND job_name IN ('INTRADAY_SCAN','DAILY_SCAN') GROUP BY status ORDER BY status") List<Map<String,Object>> jobs(LocalDate date);
    @Insert("INSERT INTO daily_group_report(trade_date,destination_hash,payload) VALUES(#{date},#{hash},#{payload}) ON CONFLICT DO NOTHING")
    int enqueue(@Param("date") LocalDate date,@Param("hash") String hash,@Param("payload") String payload);
    @Select("SELECT id,trade_date,status,attempts,payload,claim_token FROM daily_group_report WHERE trade_date=#{date} AND destination_hash=#{hash}") Delivery report(@Param("date") LocalDate date,@Param("hash") String hash);
    @Update("UPDATE daily_group_report SET status='UNKNOWN',last_error='LEASE_EXPIRED',claim_token=NULL,lease_until=NULL WHERE status='SENDING' AND lease_until<now()") int expire();
    @Select(value="""
        WITH c AS (SELECT id FROM daily_group_report WHERE destination_hash=#{hash} AND status IN ('PENDING','RETRY')
            AND attempts<3 AND (next_retry_at IS NULL OR next_retry_at<=now()) ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 1)
        UPDATE daily_group_report r SET status='SENDING',attempts=attempts+1,claim_token=#{token},lease_until=now()+interval '120 seconds'
        FROM c WHERE r.id=c.id RETURNING r.*
        """,affectData=true)
    @Options(flushCache=Options.FlushCachePolicy.TRUE,useCache=false) Delivery claim(@Param("hash") String hash,@Param("token") String token);
    @Update("UPDATE daily_group_report SET status=#{status},last_error=#{error},sent_at=CASE WHEN #{status}='SUCCESS' THEN now() ELSE sent_at END,next_retry_at=CASE WHEN #{status}='RETRY' THEN now()+interval '60 seconds' ELSE NULL END,claim_token=NULL,lease_until=NULL WHERE id=#{id} AND status='SENDING' AND claim_token=#{token}")
    int delivered(@Param("id") long id,@Param("token") String token,@Param("status") String status,@Param("error") String error);
}
