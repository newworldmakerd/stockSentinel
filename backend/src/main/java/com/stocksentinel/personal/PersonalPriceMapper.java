package com.stocksentinel.personal;

import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface PersonalPriceMapper {
    class Config {public Long id,revision;public String groupHash,userId,symbol;public Boolean enabled;public BigDecimal takeProfit,stopLoss;public LocalDate takeProfitFiredOn,stopLossFiredOn;public OffsetDateTime lastBarAt;}
    class Event {public Long id,configId,revision;public String groupHash,eventType,payload,status,claimToken;public OffsetDateTime barAt;public BigDecimal referencePrice;public Integer attempts;}
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{key},763015))") Object lockKey(String key);
    @Select("SELECT * FROM personal_price_alert WHERE group_hash=#{group} AND user_id=#{user} AND symbol=#{symbol} FOR UPDATE")
    Config lock(@Param("group") String group,@Param("user") String user,@Param("symbol") String symbol);
    @Select("SELECT * FROM personal_price_alert WHERE group_hash=#{group} AND user_id=#{user} AND symbol=#{symbol}")
    Config get(@Param("group") String group,@Param("user") String user,@Param("symbol") String symbol);
    @Insert("""
        INSERT INTO personal_price_alert(group_hash,user_id,symbol,take_profit,stop_loss) VALUES(#{groupHash},#{userId},#{symbol},#{takeProfit},#{stopLoss})
        ON CONFLICT(group_hash,user_id,symbol) DO UPDATE SET enabled=true,take_profit=EXCLUDED.take_profit,stop_loss=EXCLUDED.stop_loss,
        take_profit_fired_on=CASE WHEN personal_price_alert.take_profit IS DISTINCT FROM EXCLUDED.take_profit THEN NULL ELSE personal_price_alert.take_profit_fired_on END,
        stop_loss_fired_on=CASE WHEN personal_price_alert.stop_loss IS DISTINCT FROM EXCLUDED.stop_loss THEN NULL ELSE personal_price_alert.stop_loss_fired_on END,
        revision=personal_price_alert.revision+1,updated_at=now()
        WHERE NOT personal_price_alert.enabled OR personal_price_alert.take_profit IS DISTINCT FROM EXCLUDED.take_profit OR personal_price_alert.stop_loss IS DISTINCT FROM EXCLUDED.stop_loss
        """) int configure(Config row);
    @Update("UPDATE personal_price_alert SET enabled=false,revision=revision+1,updated_at=now() WHERE group_hash=#{group} AND user_id=#{user} AND symbol=#{symbol} AND enabled")
    int disable(@Param("group") String group,@Param("user") String user,@Param("symbol") String symbol);
    @Select("SELECT * FROM personal_price_alert WHERE group_hash=#{group} AND user_id=#{user} ORDER BY symbol LIMIT 20 OFFSET #{offset}")
    List<Config> mine(@Param("group") String group,@Param("user") String user,@Param("offset") int offset);
    @Select("SELECT DISTINCT p.symbol FROM personal_price_alert p JOIN stock_info s ON s.symbol=p.symbol WHERE p.group_hash=#{group} AND p.enabled AND s.status='ACTIVE' AND s.exchange IN ('SH','SZ') ORDER BY p.symbol")
    List<String> symbols(String group);
    @Select("SELECT * FROM personal_price_alert WHERE group_hash=#{group} AND symbol=#{symbol} AND enabled ORDER BY id FOR UPDATE")
    List<Config> active(@Param("group") String group,@Param("symbol") String symbol);
    @Update("UPDATE personal_price_alert SET last_bar_at=#{at},take_profit_fired_on=CASE WHEN #{take} THEN #{date} ELSE take_profit_fired_on END,stop_loss_fired_on=CASE WHEN #{stop} THEN #{date} ELSE stop_loss_fired_on END WHERE id=#{id}")
    int advance(@Param("id") long id,@Param("at") OffsetDateTime at,@Param("date") LocalDate date,@Param("take") boolean take,@Param("stop") boolean stop);
    @Insert("INSERT INTO personal_price_event(config_id,revision,group_hash,event_type,bar_at,reference_price,payload) VALUES(#{configId},#{revision},#{groupHash},#{eventType},#{barAt},#{referencePrice},#{payload}) ON CONFLICT DO NOTHING")
    @Options(useGeneratedKeys=true,keyProperty="id",keyColumn="id") int event(Event event);
    @Update("UPDATE personal_price_event e SET status=CASE WHEN status='SENDING' THEN 'UNKNOWN' WHEN bar_at<=now()-interval '15 minutes' THEN 'EXPIRED' ELSE 'CANCELLED' END,last_error='LEASE_DATA_OR_CONFIG_INVALID',claim_token=NULL,lease_until=NULL WHERE (status='SENDING' AND lease_until<now()) OR (status IN ('PENDING','RETRY') AND (bar_at<=now()-interval '15 minutes' OR NOT EXISTS(SELECT 1 FROM personal_price_alert p WHERE p.id=e.config_id AND p.enabled)))") int expire();
    @Select(value="""
        WITH c AS (SELECT e.id FROM personal_price_event e JOIN personal_price_alert p ON p.id=e.config_id
            WHERE e.group_hash=#{group} AND e.status IN ('PENDING','RETRY') AND e.attempts<3 AND p.enabled
            AND e.bar_at>now()-interval '15 minutes' AND (e.next_retry_at IS NULL OR e.next_retry_at<=now()) ORDER BY e.id FOR UPDATE OF e SKIP LOCKED LIMIT 1)
        UPDATE personal_price_event e SET status='SENDING',attempts=attempts+1,claim_token=#{token},lease_until=now()+interval '120 seconds'
        FROM c WHERE e.id=c.id RETURNING e.id,e.payload,e.attempts,e.claim_token
        """,affectData=true)
    @Options(flushCache=Options.FlushCachePolicy.TRUE,useCache=false) Event claim(@Param("group") String group,@Param("token") String token);
    @Update("UPDATE personal_price_event SET status=#{status},sent_at=CASE WHEN #{status}='SUCCESS' THEN now() ELSE sent_at END,last_error=#{error},next_retry_at=CASE WHEN #{status}='RETRY' THEN now()+interval '30 seconds' ELSE NULL END,claim_token=NULL,lease_until=NULL WHERE id=#{id} AND status='SENDING' AND claim_token=#{token}")
    int finish(@Param("id") long id,@Param("token") String token,@Param("status") String status,@Param("error") String error);
}
