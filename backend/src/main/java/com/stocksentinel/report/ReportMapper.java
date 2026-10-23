package com.stocksentinel.report;

import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import com.stocksentinel.common.entity.StockInfo;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ReportMapper {
    @Select("SELECT p.symbol,s.name,p.reference_high,p.average_amount,p.details::text AS details FROM pullback_candidate p JOIN stock_info s USING(symbol) WHERE p.data_date=#{date} AND p.version=#{version} ORDER BY p.average_amount DESC,p.symbol LIMIT 5")
    List<Map<String,Object>> pullbacks(@Param("date") LocalDate date,@Param("version") String version);
    class Run {public LocalDate tradeDate,asOf;public String status,overview;public Integer total;}
    class Delivery {public Long id;public LocalDate tradeDate;public String payload,status,claimToken;public Integer attempts;}
    @Select("SELECT * FROM stock_info WHERE exchange IN ('SH','SZ') AND status='ACTIVE' ORDER BY symbol") List<StockInfo> universe();
    @Select("SELECT max(trade_date) FROM market_calendar WHERE is_open AND exchange='CN' AND trade_date<#{date}") LocalDate previousOpen(LocalDate date);
    @Insert("INSERT INTO market_screen_run(trade_date,as_of,total) VALUES(#{date},#{asOf},#{total}) ON CONFLICT DO NOTHING")
    int start(@Param("date") LocalDate date,@Param("asOf") LocalDate asOf,@Param("total") int total);
    @Select("SELECT trade_date,as_of,status,total,overview::text AS overview FROM market_screen_run WHERE trade_date=#{date}") Run run(LocalDate date);
    @Insert("INSERT INTO market_screen_item(trade_date,symbol) SELECT #{date},symbol FROM stock_info WHERE exchange IN ('SH','SZ') AND status='ACTIVE' ON CONFLICT DO NOTHING") int seed(LocalDate date);
    @Select("SELECT s.* FROM stock_info s JOIN market_screen_item i ON i.symbol=s.symbol WHERE i.trade_date=#{date} AND i.status='PENDING' ORDER BY EXISTS(SELECT 1 FROM scan_retry_item r WHERE r.pipeline='SCREENING' AND r.run_date=i.trade_date AND r.symbol=i.symbol AND r.status='QUEUED') DESC,(EXISTS(SELECT 1 FROM personal_position p WHERE p.symbol=s.symbol AND quantity>0) OR EXISTS(SELECT 1 FROM trend_follow_lot l WHERE l.symbol=s.symbol AND remaining>0)) DESC,EXISTS(SELECT 1 FROM stock_watchlist w WHERE w.symbol=s.symbol AND enabled) DESC,EXISTS(SELECT 1 FROM market_daily_bar b WHERE b.symbol=s.symbol) DESC,s.symbol") List<StockInfo> pending(LocalDate date);
    @Update("UPDATE market_screen_run SET overview=CAST(#{json} AS jsonb) WHERE trade_date=#{date}") int overview(@Param("date") LocalDate date,@Param("json") String json);
    @Update("UPDATE market_screen_run SET status=#{status},finished_at=now() WHERE trade_date=#{date}") int finishRun(@Param("date") LocalDate date,@Param("status") String status);
    @Select(value="""
        WITH updated AS(UPDATE market_screen_item SET status=#{status},code=#{code},average_amount=#{amount},details=CAST(#{details} AS jsonb),
        attempts=attempts+1,last_attempt_at=now(),last_success_at=CASE WHEN #{status} IN ('CANDIDATE','NO_MATCH') THEN now() ELSE last_success_at END,
        last_error_code=CASE WHEN #{status}='FAILED' THEN #{code} ELSE last_error_code END
        WHERE trade_date=#{date} AND symbol=#{symbol} RETURNING trade_date,symbol,status,code,attempts),
        done AS(UPDATE scan_retry_item r SET status=CASE WHEN u.status IN ('CANDIDATE','NO_MATCH') THEN 'SUCCESS' WHEN u.status='FAILED' THEN 'FAILED' ELSE 'SKIPPED' END,
        attempts_after=u.attempts,result_code=COALESCE(u.code,u.status),completed_at=now() FROM updated u
        WHERE r.pipeline='SCREENING' AND r.run_date=u.trade_date AND r.symbol=u.symbol AND r.status='QUEUED' RETURNING r.symbol)
        SELECT count(*) FROM updated
        """,affectData=true) @Options(flushCache=Options.FlushCachePolicy.TRUE,useCache=false)
    int item(@Param("date") LocalDate date,@Param("symbol") String symbol,@Param("status") String status,@Param("code") String code,@Param("amount") BigDecimal amount,@Param("details") String details);
    @Update("UPDATE market_screen_item SET explanation=#{text} WHERE trade_date=#{date} AND symbol=#{symbol} AND status='CANDIDATE'") int explanation(@Param("date") LocalDate date,@Param("symbol") String symbol,@Param("text") String text);
    @Select("SELECT i.symbol,s.name,i.average_amount,i.details::text AS details,i.explanation FROM market_screen_item i JOIN stock_info s ON s.symbol=i.symbol WHERE i.trade_date=#{date} AND i.status='CANDIDATE' ORDER BY average_amount DESC,i.symbol LIMIT 5") List<Map<String,Object>> candidates(LocalDate date);
    @Select("SELECT status,count(*) AS count FROM market_screen_item WHERE trade_date=#{date} GROUP BY status ORDER BY status") List<Map<String,Object>> coverage(LocalDate date);
    @Select("SELECT CASE WHEN count(*)=20 AND count(amount)=20 THEN avg(amount) ELSE NULL END FROM (SELECT amount FROM market_daily_bar WHERE symbol=#{symbol} AND adjustment='NONE' AND trade_date<=#{date} ORDER BY trade_date DESC LIMIT 20) x")
    BigDecimal average(@Param("symbol") String symbol,@Param("date") LocalDate date);
    @Select("SELECT count(*) FROM market_daily_bar WHERE symbol=#{symbol} AND adjustment='NONE' AND trade_date<=#{date}") int history(@Param("symbol") String symbol,@Param("date") LocalDate date);
    @Select("SELECT min(trade_date) FROM market_daily_bar WHERE symbol=#{symbol} AND adjustment=#{adjustment}")
    LocalDate earliest(@Param("symbol") String symbol,@Param("adjustment") String adjustment);
    @Select("SELECT details::text FROM market_screen_item WHERE trade_date=#{date} AND symbol=#{symbol} AND status IN ('CANDIDATE','NO_MATCH')")
    String stockDetails(@Param("date") LocalDate date,@Param("symbol") String symbol);
    @Update("UPDATE market_screen_item SET status='PENDING',code=NULL WHERE trade_date=#{date} AND (status='FAILED' OR (status='EXCLUDED' AND code='NO_CURRENT_TRADES'))") int retryItems(LocalDate date);
    @Update("UPDATE market_screen_run SET status='RUNNING',finished_at=NULL WHERE trade_date=#{date} AND status IN ('SOURCE_UNAVAILABLE','FAILED','PARTIAL','OVERVIEW_UNAVAILABLE','INTERRUPTED')") int reopen(LocalDate date);
    @Select("SELECT signal_type,count(*) AS count FROM signal_event WHERE trade_date=#{date} GROUP BY signal_type ORDER BY signal_type") List<Map<String,Object>> signals(LocalDate date);
    @Select("SELECT status,count(*) AS count FROM job_execution WHERE trade_date=#{date} AND job_name IN ('INTRADAY_SCAN','DAILY_SCAN') GROUP BY status ORDER BY status") List<Map<String,Object>> jobs(LocalDate date);
    @Insert("INSERT INTO daily_group_report(trade_date,destination_hash,payload) VALUES(#{date},#{hash},#{payload}) ON CONFLICT DO NOTHING")
    int enqueue(@Param("date") LocalDate date,@Param("hash") String hash,@Param("payload") String payload);
    @Select("SELECT id,trade_date,status,attempts,payload,claim_token FROM daily_group_report WHERE trade_date=#{date} AND destination_hash=#{hash}") Delivery report(@Param("date") LocalDate date,@Param("hash") String hash);
    @Insert("INSERT INTO notify_record(channel,message_type,idempotency_key,target_chat_id,payload) VALUES('WECOM_SMART_BOT','REPORT_RESEND',#{key},#{chat},#{payload}) ON CONFLICT DO NOTHING")
    int enqueueResend(@Param("key") String key,@Param("chat") String chat,@Param("payload") String payload);
    @Select(value="""
        WITH c AS (SELECT id FROM notify_record WHERE message_type='REPORT_RESEND' AND target_chat_id=#{chat}
        AND status IN ('PENDING','RETRY') AND attempts<3 AND (next_retry_at IS NULL OR next_retry_at<=now())
        ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 1)
        UPDATE notify_record n SET status='SENDING',attempts=attempts+1,claim_token=#{token},lease_until=now()+interval '120 seconds',next_retry_at=NULL
        FROM c WHERE n.id=c.id RETURNING n.*
        """,affectData=true)
    @Options(flushCache=Options.FlushCachePolicy.TRUE,useCache=false)
    com.stocksentinel.notification.NotificationRow claimResend(@Param("chat") String chat,@Param("token") String token);
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
