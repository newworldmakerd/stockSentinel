package com.stocksentinel.personal;

import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import org.apache.ibatis.annotations.*;
import com.stocksentinel.notification.NotificationRow;

@Mapper
public interface PositionMapper {
    class Position {public String groupHash,userId,symbol;public Long quantity,sellableQuantity,revision;public BigDecimal costPrice;public LocalDate reportedOn;public OffsetDateTime updatedAt;}
    @Insert("""
        INSERT INTO personal_position(group_hash,user_id,symbol,quantity,cost_price,sellable_quantity,reported_on)
        VALUES(#{groupHash},#{userId},#{symbol},#{quantity},#{costPrice},#{sellableQuantity},#{reportedOn})
        ON CONFLICT(group_hash,user_id,symbol) DO UPDATE SET quantity=EXCLUDED.quantity,cost_price=EXCLUDED.cost_price,
        sellable_quantity=EXCLUDED.sellable_quantity,reported_on=EXCLUDED.reported_on,revision=personal_position.revision+1,updated_at=now()
        """) int save(Position row);
    @Select("SELECT * FROM personal_position WHERE group_hash=#{group} AND user_id=#{user} AND symbol=#{symbol}")
    Position get(@Param("group") String group,@Param("user") String user,@Param("symbol") String symbol);
    @Select("SELECT * FROM personal_position WHERE group_hash=#{group} AND user_id=#{user} ORDER BY symbol LIMIT 20 OFFSET #{offset}")
    List<Position> mine(@Param("group") String group,@Param("user") String user,@Param("offset") int offset);
    @Select("SELECT * FROM personal_position WHERE group_hash=#{group} AND symbol=#{symbol} ORDER BY user_id FOR SHARE")
    List<Position> holders(@Param("group") String group,@Param("symbol") String symbol);
    @Insert("INSERT INTO notify_record(signal_id,channel,message_type,idempotency_key,target_chat_id,payload) VALUES(#{signal},'WECOM_SMART_BOT','PERSONAL_POSITION',#{key},#{chat},#{payload}) ON CONFLICT DO NOTHING")
    int enqueue(@Param("signal") long signal,@Param("key") String key,@Param("chat") String chat,@Param("payload") String payload);
    @Update("UPDATE notify_record SET status='EXPIRED',last_error='POSITION_MESSAGE_EXPIRED',next_retry_at=NULL WHERE message_type='PERSONAL_POSITION' AND status IN ('PENDING','RETRY') AND created_at<=now()-interval '15 minutes'") int expire();
    @Select(value="""
        WITH c AS (SELECT id FROM notify_record WHERE message_type='PERSONAL_POSITION' AND target_chat_id=#{chat}
        AND status IN ('PENDING','RETRY') AND created_at>now()-interval '15 minutes' AND attempts<3 AND (next_retry_at IS NULL OR next_retry_at<=now())
        ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 1)
        UPDATE notify_record n SET status='SENDING',attempts=attempts+1,claim_token=#{token},lease_until=now()+interval '120 seconds',next_retry_at=NULL
        FROM c WHERE n.id=c.id RETURNING n.*
        """,affectData=true)
    @Options(flushCache=Options.FlushCachePolicy.TRUE,useCache=false)
    NotificationRow claim(@Param("chat") String chat,@Param("token") String token);
}
