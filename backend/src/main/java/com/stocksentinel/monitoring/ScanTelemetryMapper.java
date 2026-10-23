package com.stocksentinel.monitoring;

import java.time.*;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ScanTelemetryMapper {
    class Step {public long jobId,elapsedMs;public String symbol,stage,status,code,source;public OffsetDateTime dataObservedAt,dataBasisAt,startedAt,finishedAt;}
    @Insert("""
        INSERT INTO scan_step(job_id,symbol,stage,status,code,source,data_observed_at,data_basis_at,started_at,finished_at,elapsed_ms)
        VALUES(#{jobId},#{symbol},#{stage},#{status},#{code},#{source},#{dataObservedAt},#{dataBasisAt},#{startedAt},#{finishedAt},#{elapsedMs})
        """) int insert(Step row);
    @Insert("INSERT INTO scan_signal_link(job_id,signal_id) VALUES(#{job},#{signal}) ON CONFLICT DO NOTHING")
    int link(@Param("job") long job,@Param("signal") long signal);
    @Select("""
        SELECT job_id,symbol,stage,status,code,source,data_observed_at,data_basis_at,started_at,finished_at,elapsed_ms
        FROM scan_step WHERE job_id=#{job} ORDER BY id LIMIT 500
        """) List<Map<String,Object>> steps(long job);
    @Select("SELECT EXISTS(SELECT 1 FROM job_execution WHERE id=#{job})") boolean exists(long job);
    @Select("""
        SELECT n.id,n.message_type,n.status,n.attempts,n.created_at AS queued_at,n.sent_at AS delivery_confirmed_at,
        CASE WHEN n.last_error IS NULL THEN NULL WHEN n.last_error ~ '^[A-Z0-9_]{1,80}$' THEN n.last_error ELSE 'DELIVERY_FAILURE' END AS delivery_code,
        s.created_at AS signal_recorded_at,s.strategy_code,s.strategy_version,
        s.signal_at AS signal_basis_at,t.job_id AS observation_job_id,t.strategy_version AS observation_version,
        t.data_basis_at AS observation_basis_at,t.indicator_observed_at,t.evaluated_at,r.source AS indicator_source
        FROM notify_record n LEFT JOIN signal_event s ON s.id=n.signal_id
        LEFT JOIN t_observation_notice t ON t.idempotency_key=n.idempotency_key
        LEFT JOIN LATERAL(SELECT source FROM scan_step WHERE job_id=t.job_id AND symbol=t.symbol AND stage='T_RSI_FETCH' ORDER BY id DESC LIMIT 1) r ON true
        WHERE n.id=#{id}
        """) Map<String,Object> trace(long id);
    @Select("SELECT job_id FROM scan_signal_link WHERE signal_id=(SELECT signal_id FROM notify_record WHERE id=#{id}) ORDER BY job_id") List<Long> jobs(long id);
}
