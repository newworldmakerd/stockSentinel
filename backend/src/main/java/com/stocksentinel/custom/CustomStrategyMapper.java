package com.stocksentinel.custom;
import java.time.*;import java.util.*;import org.apache.ibatis.annotations.*;
import com.stocksentinel.notification.NotificationRow;
@Mapper
public interface CustomStrategyMapper {
    class Header {public UUID id;public int currentRevision;public Integer activeRevision;}
    class Revision {public UUID strategyId;public int revision;public String name,description,definition;}
    @Select("SELECT * FROM custom_daily_strategy WHERE id=#{id} FOR UPDATE")Header locked(UUID id);
    @Select("SELECT * FROM custom_daily_strategy WHERE id=#{id}")Header header(UUID id);
    @Select("SELECT s.id,s.current_revision,s.active_revision,r.name,r.description,s.updated_at FROM custom_daily_strategy s JOIN custom_daily_revision r ON r.strategy_id=s.id AND r.revision=s.current_revision ORDER BY s.updated_at DESC LIMIT 100")List<Map<String,Object>> list();
    @Select("SELECT strategy_id,revision,name,description,definition::text FROM custom_daily_revision WHERE strategy_id=#{id} AND revision=#{revision}")Revision revision(@Param("id")UUID id,@Param("revision")int revision);
    @Select("SELECT revision,name,created_at FROM custom_daily_revision WHERE strategy_id=#{id} ORDER BY revision DESC LIMIT 100")List<Map<String,Object>> revisions(UUID id);
    @Insert("INSERT INTO custom_daily_strategy(id,current_revision) VALUES(#{id},1)")int insert(UUID id);
    @Insert("INSERT INTO custom_daily_revision(strategy_id,revision,name,description,definition) VALUES(#{strategyId},#{revision},#{name},#{description},CAST(#{definition} AS jsonb))")int revisionInsert(Revision revision);
    @Update("UPDATE custom_daily_strategy SET current_revision=#{revision},updated_at=now() WHERE id=#{id}")int current(@Param("id")UUID id,@Param("revision")int revision);
    @Update("UPDATE custom_daily_strategy SET active_revision=#{revision},updated_at=now() WHERE id=#{id}")int active(@Param("id")UUID id,@Param("revision")Integer revision);
    @Select("SELECT symbol FROM stock_watchlist WHERE enabled ORDER BY symbol LIMIT 101")List<String> watchlist();
    @Select("SELECT DISTINCT symbol FROM personal_position WHERE quantity>0 ORDER BY symbol LIMIT 101")List<String> positions();
    @Select("SELECT max(trade_date) FROM market_daily_bar WHERE trade_date<#{today}")LocalDate suggestedDate(LocalDate today);
    @Select("SELECT snapshot::text FROM chip_scan_item WHERE symbol=#{symbol} AND trade_date=#{date} AND status IN ('MATCHED','NO_MATCH')")String chips(@Param("symbol")String symbol,@Param("date")LocalDate date);
    @Select("SELECT count(*) FROM custom_daily_run WHERE strategy_id=#{id} AND revision=#{revision} AND kind='PREVIEW' AND evaluated>0 AND skipped=0")int validated(@Param("id")UUID id,@Param("revision")int revision);
    @Select("SELECT results::text FROM custom_daily_run WHERE strategy_id=#{id} ORDER BY created_at DESC LIMIT 20")List<String> runs(UUID id);
    @Select("SELECT request_hash,results::text FROM custom_daily_run WHERE strategy_id=#{id} AND request_id=#{request}")Map<String,Object> request(@Param("id")UUID id,@Param("request")String request);
    @Insert("INSERT INTO custom_daily_run(id,strategy_id,revision,trade_date,kind,request_id,request_hash,results,evaluated,skipped,matched) VALUES(#{run},#{strategy},#{revision},#{date},#{kind},#{request},#{hash},CAST(#{results} AS jsonb),#{evaluated},#{skipped},#{matched})")int run(@Param("run")UUID run,@Param("strategy")UUID strategy,@Param("revision")int revision,@Param("date")LocalDate date,@Param("kind")String kind,@Param("request")String request,@Param("hash")String hash,@Param("results")String results,@Param("evaluated")int evaluated,@Param("skipped")int skipped,@Param("matched")int matched);
    @Insert("INSERT INTO custom_daily_event(id,strategy_id,revision,symbol,trade_date,snapshot) VALUES(#{event},#{strategy},#{revision},#{symbol},#{date},CAST(#{snapshot} AS jsonb)) ON CONFLICT DO NOTHING")int event(@Param("event")UUID event,@Param("strategy")UUID strategy,@Param("revision")int revision,@Param("symbol")String symbol,@Param("date")LocalDate date,@Param("snapshot")String snapshot);
    @Select("SELECT id FROM custom_daily_event WHERE strategy_id=#{strategy} AND revision=#{revision} AND symbol=#{symbol} AND trade_date=#{date}")UUID eventId(@Param("strategy")UUID strategy,@Param("revision")int revision,@Param("symbol")String symbol,@Param("date")LocalDate date);
    @Insert("INSERT INTO custom_daily_notice(event_id,target_chat_id,payload) VALUES(#{event},#{chat},#{text}) ON CONFLICT DO NOTHING")int notice(@Param("event")UUID event,@Param("chat")String chat,@Param("text")String text);
    @Update("UPDATE custom_daily_notice SET status='UNKNOWN',last_error='LEASE_EXPIRED',claim_token=NULL WHERE status='SENDING' AND lease_until<now()")int expireSending();
    @Update("UPDATE custom_daily_notice SET status='EXPIRED',last_error='STALE_OR_PAUSED' WHERE status IN ('PENDING','RETRY') AND(created_at<now()-interval '15 minutes' OR NOT EXISTS(SELECT 1 FROM custom_daily_event e JOIN custom_daily_strategy s ON s.id=e.strategy_id AND s.active_revision=e.revision WHERE e.id=custom_daily_notice.event_id))")int expirePending();
    @Select(value="WITH c AS(SELECT n.id FROM custom_daily_notice n JOIN custom_daily_event e ON e.id=n.event_id JOIN custom_daily_strategy s ON s.id=e.strategy_id AND s.active_revision=e.revision WHERE n.target_chat_id=#{chat} AND n.status IN ('PENDING','RETRY') AND n.attempts<3 AND(n.next_retry_at IS NULL OR n.next_retry_at<=now()) AND n.created_at>=now()-interval '15 minutes' ORDER BY n.id FOR UPDATE OF n SKIP LOCKED LIMIT 1) UPDATE custom_daily_notice n SET status='SENDING',attempts=attempts+1,claim_token=#{token},lease_until=now()+interval '120 seconds' FROM c WHERE n.id=c.id RETURNING n.*",affectData=true)@Options(flushCache=Options.FlushCachePolicy.TRUE,useCache=false)NotificationRow claim(@Param("chat")String chat,@Param("token")String token);
    @Update("UPDATE custom_daily_notice SET status=#{status},last_error=#{code},sent_at=CASE WHEN #{status}='SUCCESS' THEN now() ELSE sent_at END,next_retry_at=CASE WHEN #{status}='RETRY' THEN now()+interval '60 seconds' ELSE NULL END,claim_token=NULL WHERE id=#{id} AND status='SENDING' AND claim_token=#{token}")int finish(@Param("id")long id,@Param("token")String token,@Param("status")String status,@Param("code")String code);
}
