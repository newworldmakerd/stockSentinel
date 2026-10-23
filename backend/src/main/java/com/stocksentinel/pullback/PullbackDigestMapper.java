package com.stocksentinel.pullback;

import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface PullbackDigestMapper {
    @Select("SELECT pg_try_advisory_xact_lock(hashtextextended(#{hash},763014))")
    boolean queueLock(String hash);
    @Select("""
        SELECT DISTINCT right(l.line,6) FROM pullback_digest d
        CROSS JOIN LATERAL unnest(string_to_array(d.payload,chr(10))) AS l(line)
        WHERE d.destination_hash=#{hash} AND (d.slot_at AT TIME ZONE 'Asia/Shanghai')::date=#{date}
          AND d.status IN ('SUCCESS','UNKNOWN','SENDING','PENDING','RETRY')
          AND l.line ~ '^[1-5][.] .+ [0-9]{6}$'
        """)
    List<String> recommended(@Param("date") LocalDate date,@Param("hash") String hash);
    class Delivery {public Long id;public String payload,claimToken,status;public Integer attempts;}
    @Insert("INSERT INTO pullback_digest(slot_at,destination_hash,payload) VALUES(#{at},#{hash},#{payload}) ON CONFLICT DO NOTHING")
    int enqueue(@Param("at") OffsetDateTime at,@Param("hash") String hash,@Param("payload") String payload);
    @Update("UPDATE pullback_digest SET status=CASE WHEN status='SENDING' THEN 'UNKNOWN' ELSE 'EXPIRED' END,last_error='SLOT_OR_LEASE_EXPIRED',claim_token=NULL,lease_until=NULL WHERE (status='SENDING' AND lease_until<now()) OR (status IN ('PENDING','RETRY') AND slot_at<=now()-interval '10 minutes')")
    int expire();
    @Select(value="""
        WITH c AS (SELECT id FROM pullback_digest WHERE destination_hash=#{hash} AND status IN ('PENDING','RETRY')
            AND attempts<3 AND slot_at<=now() AND slot_at>now()-interval '10 minutes'
            AND (next_retry_at IS NULL OR next_retry_at<=now()) ORDER BY slot_at FOR UPDATE SKIP LOCKED LIMIT 1)
        UPDATE pullback_digest d SET status='SENDING',attempts=attempts+1,claim_token=#{token},lease_until=now()+interval '120 seconds'
        FROM c WHERE d.id=c.id RETURNING d.id,d.payload,d.status,d.attempts,d.claim_token
        """,affectData=true)
    @Options(flushCache=Options.FlushCachePolicy.TRUE,useCache=false)
    Delivery claim(@Param("hash") String hash,@Param("token") String token);
    @Update("UPDATE pullback_digest SET status=#{status},sent_at=CASE WHEN #{status}='SUCCESS' THEN now() ELSE sent_at END,last_error=#{error},next_retry_at=CASE WHEN #{status}='RETRY' THEN now()+interval '30 seconds' ELSE NULL END,claim_token=NULL,lease_until=NULL WHERE id=#{id} AND status='SENDING' AND claim_token=#{token}")
    int finish(@Param("id") long id,@Param("token") String token,@Param("status") String status,@Param("error") String error);
}
