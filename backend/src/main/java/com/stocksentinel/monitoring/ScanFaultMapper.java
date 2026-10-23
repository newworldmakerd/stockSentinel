package com.stocksentinel.monitoring;

import java.time.*;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ScanFaultMapper {
    class Scan {public Long id;public String status,errorMessage;public LocalDate tradeDate;public OffsetDateTime startedAt;public Integer processedCount;}
    class Item {public String symbol,name,status,code;}
    class State {public String destinationHash,failureKind,incidentId;public Integer streak;public Boolean active;public Long lastJobId,seq;public OffsetDateTime lastScanAt,lastAlertAt;}
    class Event {public Long id,jobId,seq;public String destinationHash,failureKind,incidentId,eventType,payload,status,claimToken;public Integer attempts;}

    @Select("SELECT id,status,error_message,trade_date,started_at,processed_count FROM job_execution WHERE id=#{id} AND job_name='INTRADAY_SCAN' AND status<>'RUNNING'") Scan scan(long id);
    @Select("SELECT i.symbol,s.name,i.status,i.code FROM job_execution_item i LEFT JOIN stock_info s ON s.symbol=i.symbol WHERE i.job_id=#{id} ORDER BY i.id") List<Item> items(long id);
    @Select("SELECT EXISTS(SELECT 1 FROM scan_step WHERE job_id=#{id} AND stage='T_RSI_OBSERVATION' AND status='SUCCESS')") boolean tRsiEvaluated(long id);
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{hash},763016))") Object lockDestination(String hash);
    @Insert("INSERT INTO scan_fault_state(destination_hash,failure_kind) VALUES(#{hash},#{kind}) ON CONFLICT DO NOTHING")
    int ensure(@Param("hash") String hash,@Param("kind") String kind);
    @Select("SELECT * FROM scan_fault_state WHERE destination_hash=#{hash} AND failure_kind=#{kind} FOR UPDATE")
    State lock(@Param("hash") String hash,@Param("kind") String kind);
    @Update("UPDATE scan_fault_state SET streak=#{streak},active=#{active},incident_id=#{incidentId},last_job_id=#{lastJobId},last_scan_at=#{lastScanAt},last_alert_at=#{lastAlertAt},seq=#{seq} WHERE destination_hash=#{destinationHash} AND failure_kind=#{failureKind}") int save(State state);
    @Insert("INSERT INTO scan_fault_event(destination_hash,failure_kind,incident_id,seq,event_type,job_id,payload) VALUES(#{destinationHash},#{failureKind},#{incidentId},#{seq},#{eventType},#{jobId},#{payload}) ON CONFLICT DO NOTHING") int enqueue(Event event);
    @Update("UPDATE scan_fault_event SET status='CANCELLED',last_error='RECOVERED_BEFORE_SEND' WHERE destination_hash=#{hash} AND failure_kind=#{kind} AND incident_id=#{incident} AND event_type='FAULT' AND status IN ('PENDING','RETRY')")
    int cancelFault(@Param("hash") String hash,@Param("kind") String kind,@Param("incident") String incident);
    @Update("UPDATE scan_fault_event SET status=CASE WHEN status='SENDING' THEN 'UNKNOWN' ELSE 'EXPIRED' END,last_error='LEASE_OR_ALERT_EXPIRED',claim_token=NULL,lease_until=NULL WHERE (status='SENDING' AND lease_until<now()) OR (status IN ('PENDING','RETRY') AND created_at<=now()-interval '15 minutes')") int expire();
    @Select(value="""
        WITH c AS (SELECT e.id FROM scan_fault_event e
          WHERE e.destination_hash=#{hash} AND e.status IN ('PENDING','RETRY') AND e.attempts<3
            AND e.created_at>now()-interval '15 minutes' AND (e.next_retry_at IS NULL OR e.next_retry_at<=now())
            AND (e.event_type='FAULT' OR EXISTS(SELECT 1 FROM scan_fault_event f
                WHERE f.destination_hash=e.destination_hash AND f.failure_kind=e.failure_kind AND f.incident_id=e.incident_id
                  AND f.event_type='FAULT' AND f.status IN ('SUCCESS','UNKNOWN')))
          ORDER BY e.id FOR UPDATE OF e SKIP LOCKED LIMIT 1)
        UPDATE scan_fault_event e SET status='SENDING',attempts=attempts+1,claim_token=#{token},lease_until=now()+interval '120 seconds'
        FROM c WHERE e.id=c.id RETURNING e.id,e.payload,e.attempts,e.claim_token
        """,affectData=true)
    @Options(flushCache=Options.FlushCachePolicy.TRUE,useCache=false) Event claim(@Param("hash") String hash,@Param("token") String token);
    @Update("UPDATE scan_fault_event SET status=#{status},sent_at=CASE WHEN #{status}='SUCCESS' THEN now() ELSE sent_at END,last_error=#{error},next_retry_at=CASE WHEN #{status}='RETRY' THEN now()+interval '30 seconds' ELSE NULL END,claim_token=NULL,lease_until=NULL WHERE id=#{id} AND status='SENDING' AND claim_token=#{token}")
    int finish(@Param("id") long id,@Param("token") String token,@Param("status") String status,@Param("error") String error);
}
