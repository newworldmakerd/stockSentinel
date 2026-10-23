package com.stocksentinel.trend;
import java.time.*;import java.math.BigDecimal;import java.util.*;
import org.apache.ibatis.annotations.*;
import com.stocksentinel.common.entity.StockInfo;
@Mapper
public interface TrendMapper {
    class Item {public LocalDate asOf;public String symbol,name,status,code,metrics;public Integer attempts;}
    class Lot {public Long id,quantity,remaining;public String groupHash,userId,symbol,status,atrSnapshot;public LocalDate entryDate,stopAsOf,lastCloseDate;public OffsetDateTime entryAt;public BigDecimal entryPrice,initialAtr,initialStop,stopPrice,highestClose;}
    class Account {public BigDecimal equity,marketValue;public LocalDate reportedOn;}
    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(#{key},76380))") int lock(String key);
    @Select("SELECT * FROM stock_info WHERE exchange IN ('SH','SZ') AND status='ACTIVE' ORDER BY symbol") List<StockInfo> universe();
    @Insert("INSERT INTO trend_follow_item(as_of,symbol) SELECT #{date},symbol FROM stock_info WHERE exchange IN ('SH','SZ') AND status='ACTIVE' ON CONFLICT DO NOTHING") int seed(LocalDate date);
    String MANUAL="EXISTS(SELECT 1 FROM scan_retry_item r WHERE r.pipeline='TREND_HISTORY' AND r.run_date=t.as_of AND r.symbol=t.symbol AND r.status='QUEUED')";
    String PRIORITY=" ORDER BY "+MANUAL+" DESC,(EXISTS(SELECT 1 FROM trend_follow_lot l WHERE l.symbol=t.symbol AND remaining>0) OR EXISTS(SELECT 1 FROM personal_position p WHERE p.symbol=t.symbol AND quantity>0)) DESC,EXISTS(SELECT 1 FROM stock_watchlist w WHERE w.symbol=t.symbol AND enabled) DESC,t.symbol LIMIT 20";
    @Select("SELECT t.*,s.name FROM trend_follow_item t JOIN stock_info s USING(symbol) WHERE as_of=#{date} AND (t.status='PENDING' OR(t.status='FAILED' AND (attempts<3 AND next_retry_at<=now() OR "+MANUAL+")))"+PRIORITY) List<Item> pending(LocalDate date);
    @Select("SELECT t.*,s.name FROM trend_follow_item t JOIN stock_info s USING(symbol) WHERE as_of=#{date} AND t.status='FAILED' AND "+MANUAL+PRIORITY) List<Item> pendingManual(LocalDate date);
    @Select("SELECT s.* FROM stock_info s WHERE exchange IN ('SH','SZ') AND status='ACTIVE' AND (EXISTS(SELECT 1 FROM personal_position p WHERE p.symbol=s.symbol AND quantity>0) OR EXISTS(SELECT 1 FROM stock_watchlist w WHERE w.symbol=s.symbol AND enabled) OR EXISTS(SELECT 1 FROM trend_follow_lot l WHERE l.symbol=s.symbol AND remaining>0)) ORDER BY s.symbol") List<StockInfo> priority();
    @Select(value="""
        WITH updated AS(UPDATE trend_follow_item SET status=#{status},code=#{code},metrics=CAST(#{metrics} AS jsonb),attempts=attempts+1,
        next_retry_at=now()+interval '15 minutes',updated_at=now(),last_attempt_at=now(),
        last_success_at=CASE WHEN #{status}='READY' THEN now() ELSE last_success_at END,
        last_error_code=CASE WHEN #{status}='FAILED' THEN #{code} ELSE last_error_code END
        WHERE as_of=#{date} AND symbol=#{symbol} RETURNING as_of,symbol,status,code,attempts),
        done AS(UPDATE scan_retry_item r SET status=CASE u.status WHEN 'READY' THEN 'SUCCESS' WHEN 'FAILED' THEN 'FAILED' ELSE 'SKIPPED' END,
        attempts_after=u.attempts,result_code=COALESCE(u.code,u.status),completed_at=now() FROM updated u
        WHERE r.pipeline='TREND_HISTORY' AND r.run_date=u.as_of AND r.symbol=u.symbol AND r.status='QUEUED' RETURNING r.symbol)
        SELECT count(*) FROM updated
        """,affectData=true) @Options(flushCache=Options.FlushCachePolicy.TRUE,useCache=false)
    int item(@Param("date")LocalDate date,@Param("symbol")String symbol,@Param("status")String status,@Param("code")String code,@Param("metrics")String metrics);
    @Select("SELECT metrics::text FROM trend_follow_item WHERE as_of=#{date} AND symbol=#{symbol} AND status='READY'") String metrics(@Param("symbol")String symbol,@Param("date")LocalDate date);
    @Select("SELECT status,count(*) AS count FROM trend_follow_item WHERE as_of=#{date} GROUP BY status") List<Map<String,Object>> coverage(LocalDate date);
    @Insert("INSERT INTO trend_follow_index(as_of,snapshot) VALUES(#{date},CAST(#{data} AS jsonb)) ON CONFLICT(as_of) DO UPDATE SET snapshot=EXCLUDED.snapshot,updated_at=now()") int storeIndex(@Param("date")LocalDate date,@Param("data")String data);
    @Select("SELECT snapshot::text FROM trend_follow_index WHERE as_of=#{date}") String index(LocalDate date);
    @Insert("INSERT INTO trend_follow_runtime(name,snapshot) VALUES(#{name},CAST(#{data} AS jsonb)) ON CONFLICT(name) DO UPDATE SET snapshot=EXCLUDED.snapshot,updated_at=now()") int saveRuntime(@Param("name")String name,@Param("data")String data);
    @Select("SELECT snapshot::text FROM trend_follow_runtime WHERE name=#{name}") String runtime(String name);
    @Insert("INSERT INTO trend_follow_event(symbol,trade_date,phase,snapshot) VALUES(#{symbol},#{date},#{phase},CAST(#{snapshot} AS jsonb)) ON CONFLICT DO NOTHING") int event(@Param("symbol")String symbol,@Param("date")LocalDate date,@Param("phase")String phase,@Param("snapshot")String snapshot);
    @Select("SELECT group_hash,user_id,symbol FROM trend_follow_lot WHERE remaining>0 GROUP BY group_hash,user_id,symbol") List<Map<String,String>> held();
    @Select("SELECT * FROM trend_follow_lot WHERE remaining>0 AND symbol=#{symbol} ORDER BY id FOR UPDATE") List<Lot> stockLots(String symbol);
    @Select("SELECT * FROM trend_follow_lot WHERE id=#{id} AND group_hash=#{group} AND user_id=#{user} FOR UPDATE") Lot lot(@Param("id")long id,@Param("group")String group,@Param("user")String user);
    @Select("SELECT * FROM trend_follow_lot WHERE group_hash=#{group} AND user_id=#{user} ORDER BY id DESC LIMIT 20") List<Lot> mine(@Param("group")String group,@Param("user")String user);
    @Insert("INSERT INTO trend_follow_lot(group_hash,user_id,symbol,entry_date,entry_at,entry_price,quantity,remaining,status) VALUES(#{groupHash},#{userId},#{symbol},#{entryDate},#{entryAt},#{entryPrice},#{quantity},#{remaining},'PENDING_DATA')") @Options(useGeneratedKeys=true,keyProperty="id") int newLot(Lot lot);
    @Update("UPDATE trend_follow_lot SET remaining=#{remaining},status=#{status},initial_atr=#{initialAtr},initial_stop=#{initialStop},stop_price=#{stopPrice},stop_as_of=#{stopAsOf},highest_close=#{highestClose},last_close_date=#{lastCloseDate},atr_snapshot=CAST(#{atrSnapshot} AS jsonb) WHERE id=#{id}") int saveLot(Lot lot);
    @Select("SELECT count(*) FROM trend_follow_trade WHERE message_hash=#{hash}") int message(String hash);
    @Insert("INSERT INTO trend_follow_trade(lot_id,message_hash,side,quantity,price) VALUES(#{lot},#{hash},#{side},#{qty},#{price})") int trade(@Param("lot")long lot,@Param("hash")String hash,@Param("side")String side,@Param("qty")long qty,@Param("price")BigDecimal price);
    @Select("SELECT a.* FROM trend_follow_account a WHERE a.group_hash=#{group} AND a.user_id=#{user} AND NOT EXISTS(SELECT 1 FROM trend_follow_trade t JOIN trend_follow_lot l ON l.id=t.lot_id WHERE l.group_hash=a.group_hash AND l.user_id=a.user_id AND t.recorded_at>=a.updated_at)") Account account(@Param("group")String group,@Param("user")String user);
    @Insert("INSERT INTO trend_follow_account(group_hash,user_id,equity,market_value,reported_on) VALUES(#{group},#{user},#{equity},#{value},#{date}) ON CONFLICT(group_hash,user_id) DO UPDATE SET equity=EXCLUDED.equity,market_value=EXCLUDED.market_value,reported_on=EXCLUDED.reported_on,updated_at=now()") int saveAccount(@Param("group")String group,@Param("user")String user,@Param("equity")BigDecimal equity,@Param("value")BigDecimal value,@Param("date")LocalDate date);
    @Insert("INSERT INTO trend_follow_notice(idempotency_key,target_chat_id,payload) VALUES(#{key},#{chat},#{text}) ON CONFLICT DO NOTHING") int notice(@Param("key")String key,@Param("chat")String chat,@Param("text")String text);
    @Update("UPDATE trend_follow_notice SET status='UNKNOWN',last_error='LEASE_EXPIRED',claim_token=NULL WHERE status='SENDING' AND lease_until<now()") int expire();
    @Update("UPDATE trend_follow_notice SET status='EXPIRED',last_error='TREND_NOTICE_STALE' WHERE status IN ('PENDING','RETRY') AND created_at<now()-interval '15 minutes'") int expirePending();
    @Select(value="WITH c AS(SELECT id FROM trend_follow_notice WHERE target_chat_id=#{chat} AND status IN ('PENDING','RETRY') AND attempts<3 AND(next_retry_at IS NULL OR next_retry_at<=now()) ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 1) UPDATE trend_follow_notice n SET status='SENDING',attempts=attempts+1,claim_token=#{token},lease_until=now()+interval '120 seconds' FROM c WHERE n.id=c.id RETURNING n.*",affectData=true) @Options(flushCache=Options.FlushCachePolicy.TRUE,useCache=false) com.stocksentinel.notification.NotificationRow claim(@Param("chat")String chat,@Param("token")String token);
    @Update("UPDATE trend_follow_notice SET status=#{status},last_error=#{code},sent_at=CASE WHEN #{status}='SUCCESS' THEN now() ELSE sent_at END,next_retry_at=CASE WHEN #{status}='RETRY' THEN now()+interval '60 seconds' ELSE NULL END,claim_token=NULL WHERE id=#{id} AND claim_token=#{token} AND status='SENDING'") int finish(@Param("id")long id,@Param("token")String token,@Param("status")String status,@Param("code")String code);
}
