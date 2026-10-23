package com.stocksentinel.personal;

import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import org.apache.ibatis.annotations.*;
import com.stocksentinel.notification.NotificationRow;

@Mapper
public interface TObservationMapper {
    class Day {
        public String groupHash,userId,symbol,lastCode;public LocalDate tradeDate;
        public Long positionRevision,baselineQuantity,baselineSellable,buyFilled,sellFilled;
        public BigDecimal buyPrice,sellPrice,rsi5,rsi15;
        public Boolean buyObserved,sellObserved,restoreObserved;
        public OffsetDateTime lastFiveAt,fifteenAt,updatedAt;
        public Long scanJobId;public OffsetDateTime indicatorObservedAt,evaluatedAt;
        public String rsiSource;
    }
    @Select("SELECT DISTINCT symbol FROM personal_position WHERE group_hash=#{group} AND quantity>0 AND reported_on=#{day} AND sellable_quantity>0 ORDER BY symbol")
    List<String> symbols(@Param("group") String group,@Param("day") LocalDate day);
    @Select("SELECT * FROM personal_position WHERE group_hash=#{group} AND symbol=#{symbol} AND quantity>0 AND reported_on=#{day} AND sellable_quantity>0 ORDER BY user_id FOR SHARE")
    List<PositionMapper.Position> positions(@Param("group") String group,@Param("symbol") String symbol,@Param("day") LocalDate day);
    @Select("SELECT * FROM personal_position WHERE group_hash=#{group} AND user_id=#{user} AND symbol=#{symbol} FOR SHARE")
    PositionMapper.Position position(@Param("group") String group,@Param("user") String user,@Param("symbol") String symbol);
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{key},763017))") Object lock(String key);
    @Insert("""
        INSERT INTO t_observation_day(group_hash,user_id,symbol,trade_date,position_revision,baseline_quantity,baseline_sellable)
        VALUES(#{groupHash},#{userId},#{symbol},#{tradeDate},#{positionRevision},#{baselineQuantity},#{baselineSellable}) ON CONFLICT DO NOTHING
        """) int seed(Day row);
    @Select("SELECT * FROM t_observation_day WHERE group_hash=#{group} AND user_id=#{user} AND symbol=#{symbol} AND trade_date=#{day} FOR UPDATE")
    Day get(@Param("group") String group,@Param("user") String user,@Param("symbol") String symbol,@Param("day") LocalDate day);
    @Select("SELECT * FROM t_observation_day WHERE group_hash=#{group} AND trade_date=#{day} AND buy_filled<>sell_filled ORDER BY user_id,symbol")
    List<Day> outstanding(@Param("group") String group,@Param("day") LocalDate day);
    @Select("SELECT * FROM t_observation_day WHERE group_hash=#{group} AND user_id=#{user} AND symbol=#{symbol} AND trade_date<#{day} AND buy_filled<>sell_filled ORDER BY trade_date DESC LIMIT 20")
    List<Day> previousOutstanding(@Param("group") String group,@Param("user") String user,@Param("symbol") String symbol,@Param("day") LocalDate day);
    @Update("""
        UPDATE t_observation_day SET buy_filled=#{buyFilled},sell_filled=#{sellFilled},buy_price=#{buyPrice},sell_price=#{sellPrice},
        buy_observed=#{buyObserved},sell_observed=#{sellObserved},restore_observed=#{restoreObserved},last_five_at=#{lastFiveAt},
        fifteen_at=#{fifteenAt},rsi5=#{rsi5},rsi15=#{rsi15},last_code=#{lastCode},updated_at=now()
        WHERE group_hash=#{groupHash} AND user_id=#{userId} AND symbol=#{symbol} AND trade_date=#{tradeDate}
        """) int save(Day row);
    @Insert("""
        INSERT INTO t_observation_receipt(message_hash,group_hash,user_id,symbol,trade_date,side,quantity,price)
        VALUES(#{hash},#{row.groupHash},#{row.userId},#{row.symbol},#{row.tradeDate},#{side},#{quantity},#{price}) ON CONFLICT DO NOTHING
        """) int receipt(@Param("hash") String hash,@Param("row") Day row,@Param("side") String side,@Param("quantity") long quantity,@Param("price") BigDecimal price);
    @Select("SELECT side FROM t_observation_receipt WHERE message_hash=#{hash}") String received(String hash);
    @Insert("""
        INSERT INTO t_observation_notice(idempotency_key,group_hash,user_id,symbol,trade_date,position_revision,buy_filled,sell_filled,phase,job_id,strategy_version,data_basis_at,indicator_observed_at,evaluated_at)
        VALUES(#{key},#{row.groupHash},#{row.userId},#{row.symbol},#{row.tradeDate},#{row.positionRevision},#{row.buyFilled},#{row.sellFilled},#{phase},#{row.scanJobId},'T_RSI_V1_1.0.0',#{row.lastFiveAt},#{row.indicatorObservedAt},#{row.evaluatedAt}) ON CONFLICT DO NOTHING
        """) int notice(@Param("key") String key,@Param("row") Day row,@Param("phase") String phase);
    @Insert("INSERT INTO notify_record(channel,message_type,idempotency_key,target_chat_id,payload) VALUES('WECOM_SMART_BOT','T_OBSERVATION',#{key},#{chat},#{payload}) ON CONFLICT DO NOTHING")
    int enqueue(@Param("key") String key,@Param("chat") String chat,@Param("payload") String payload);
    @Update("""
        UPDATE notify_record n SET status='EXPIRED',last_error='T_OBSERVATION_EXPIRED_OR_CHANGED',next_retry_at=NULL
        FROM t_observation_notice e JOIN t_observation_day d USING(group_hash,user_id,symbol,trade_date)
        LEFT JOIN personal_position p ON p.group_hash=e.group_hash AND p.user_id=e.user_id AND p.symbol=e.symbol
        WHERE n.idempotency_key=e.idempotency_key AND n.message_type='T_OBSERVATION' AND n.status IN ('PENDING','RETRY')
        AND (n.created_at<=now()-interval '10 minutes' OR e.trade_date<>(now() AT TIME ZONE 'Asia/Shanghai')::date
        OR (now() AT TIME ZONE 'Asia/Shanghai')::time >= time '15:00'
        OR (e.phase<>'RESTORE' AND (now() AT TIME ZONE 'Asia/Shanghai')::time >= time '14:50')
        OR e.buy_filled<>d.buy_filled OR e.sell_filled<>d.sell_filled
        OR (e.phase<>'RESTORE' AND (p.revision IS DISTINCT FROM e.position_revision OR p.reported_on<>e.trade_date OR p.quantity=0)))
        """) int expire();
    @Select(value="""
        WITH c AS (SELECT id FROM notify_record WHERE message_type='T_OBSERVATION' AND target_chat_id=#{chat}
        AND status IN ('PENDING','RETRY') AND created_at>now()-interval '10 minutes' AND attempts<3
        AND (now() AT TIME ZONE 'Asia/Shanghai')::time < time '15:00'
        AND (next_retry_at IS NULL OR next_retry_at<=now()) ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 1)
        UPDATE notify_record n SET status='SENDING',attempts=attempts+1,claim_token=#{token},lease_until=now()+interval '120 seconds',next_retry_at=NULL
        FROM c WHERE n.id=c.id RETURNING n.*
        """,affectData=true)
    @Options(flushCache=Options.FlushCachePolicy.TRUE,useCache=false)
    NotificationRow claim(@Param("chat") String chat,@Param("token") String token);
    @Select("""
        SELECT j.id AS job_id,j.started_at,j.finished_at,i.status,i.code FROM job_execution_item i
        JOIN job_execution j ON j.id=i.job_id WHERE j.job_name='INTRADAY_SCAN' AND j.trade_date=#{day} AND i.symbol=#{symbol}
        ORDER BY i.id DESC LIMIT 1
        """) Map<String,Object> latestScan(@Param("symbol") String symbol,@Param("day") LocalDate day);
}
