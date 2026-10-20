package com.stocksentinel.notification;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface NotificationMapper {
    @Insert("INSERT INTO notify_record(signal_id,channel,message_type,idempotency_key,target_chat_id) SELECT id,'WECOM_SMART_BOT',signal_type,#{key},#{target} FROM signal_event WHERE id=#{signal} ON CONFLICT(idempotency_key) DO NOTHING")
    int enqueue(@Param("signal") long signal,@Param("key") String key,@Param("target") String target);
    @Select("SELECT * FROM notify_record WHERE idempotency_key=#{key}") NotificationRow byKey(String key);
    @Select("SELECT * FROM notify_record WHERE id=#{id}") NotificationRow find(long id);
    @Select("SELECT * FROM notify_record ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}")
    List<NotificationRow> list(@Param("limit") int limit,@Param("offset") int offset);
    @Update("UPDATE notify_record SET status='UNKNOWN',last_error='WORKER_LEASE_EXPIRED',claim_token=NULL,lease_until=NULL,next_retry_at=NULL WHERE channel='WECOM_SMART_BOT' AND status='SENDING' AND lease_until < now()")
    int expire();
    @Select(value="""
        WITH candidate AS (
          SELECT id FROM notify_record WHERE channel='WECOM_SMART_BOT' AND target_chat_id IS NOT NULL
            AND status IN ('PENDING','RETRY') AND attempts < 3
            AND idempotency_key='smart-bot:' || signal_id || ':' || #{destination}
            AND (next_retry_at IS NULL OR next_retry_at <= now())
          ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 1
        ) UPDATE notify_record n SET status='SENDING',attempts=attempts+1,claim_token=#{token},
          lease_until=now()+interval '120 seconds',next_retry_at=NULL
          FROM candidate c WHERE n.id=c.id RETURNING n.*
        """,affectData=true)
    @Options(flushCache=Options.FlushCachePolicy.TRUE,useCache=false)
    NotificationRow claim(@Param("token") String token,@Param("destination") String destination);
    @Update("UPDATE notify_record SET payload=#{payload} WHERE id=#{id} AND status='SENDING' AND claim_token=#{token}")
    int payload(@Param("id") long id,@Param("token") String token,@Param("payload") String payload);
    @Update("""
        UPDATE notify_record SET status=#{status},last_error=#{error},
          sent_at=CASE WHEN #{status}='SUCCESS' THEN now() ELSE sent_at END,
          next_retry_at=CASE WHEN #{status}='RETRY' THEN now()+make_interval(secs => #{delay}) ELSE NULL END,
          claim_token=NULL,lease_until=NULL
        WHERE id=#{id} AND status='SENDING' AND claim_token=#{token}
        """)
    int finish(@Param("id") long id,@Param("token") String token,@Param("status") String status,
        @Param("error") String error,@Param("delay") int delay);
    @Select("SELECT advice::text FROM signal_advice WHERE signal_id=#{id} AND status='SUCCESS' ORDER BY created_at DESC,id DESC LIMIT 1")
    String advice(long id);
}
