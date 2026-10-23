package com.stocksentinel.monitoring;
import java.time.*;import java.util.*;import org.apache.ibatis.annotations.*;

@Mapper
public interface ScanRecoveryMapper {
    @Select("SELECT request_id,pipeline,run_date,scope_hash,scope::text,before_state::text,requested_at FROM scan_retry_request WHERE request_id=#{id}") Map<String,Object> request(String id);
    @Select("SELECT symbol,status,attempts_before,attempts_after,result_code,requested_at,completed_at FROM scan_retry_item WHERE request_id=#{id} ORDER BY symbol") List<Map<String,Object>> results(String id);
    @Insert("INSERT INTO scan_retry_request(request_id,pipeline,run_date,scope_hash,scope,before_state) VALUES(#{id},#{pipeline},#{date},#{hash},CAST(#{scope} AS jsonb),CAST(#{before} AS jsonb)) ON CONFLICT DO NOTHING")
    int requestInsert(@Param("id")String id,@Param("pipeline")String pipeline,@Param("date")LocalDate date,@Param("hash")String hash,@Param("scope")String scope,@Param("before")String before);
    @Select("""
        <script>SELECT symbol,status,code,attempts FROM trend_follow_item WHERE as_of=#{date} AND symbol IN
        <foreach collection="symbols" item="s" open="(" separator="," close=")">#{s}</foreach> ORDER BY symbol</script>
        """) List<Map<String,Object>> trendItems(@Param("date")LocalDate date,@Param("symbols")List<String> symbols);
    @Select("""
        <script>SELECT symbol,status,code,attempts FROM market_screen_item WHERE trade_date=#{date} AND symbol IN
        <foreach collection="symbols" item="s" open="(" separator="," close=")">#{s}</foreach> ORDER BY symbol</script>
        """) List<Map<String,Object>> screenItems(@Param("date")LocalDate date,@Param("symbols")List<String> symbols);
    @Insert("""
        <script>INSERT INTO scan_retry_item(request_id,pipeline,run_date,symbol,attempts_before)
        SELECT #{id},'TREND_HISTORY',as_of,symbol,attempts FROM trend_follow_item WHERE as_of=#{date} AND status='FAILED' AND symbol IN
        <foreach collection="symbols" item="s" open="(" separator="," close=")">#{s}</foreach> ON CONFLICT DO NOTHING</script>
        """) int queueTrend(@Param("id")String id,@Param("date")LocalDate date,@Param("symbols")List<String> symbols);
    @Update("""
        <script>WITH queued AS(INSERT INTO scan_retry_item(request_id,pipeline,run_date,symbol,attempts_before)
        SELECT #{id},'SCREENING',trade_date,symbol,attempts FROM market_screen_item WHERE trade_date=#{date} AND status='FAILED' AND symbol IN
        <foreach collection="symbols" item="s" open="(" separator="," close=")">#{s}</foreach> ON CONFLICT DO NOTHING RETURNING symbol)
        UPDATE market_screen_item SET status='PENDING' WHERE trade_date=#{date} AND symbol IN(SELECT symbol FROM queued)</script>
        """) int queueScreening(@Param("id")String id,@Param("date")LocalDate date,@Param("symbols")List<String> symbols);
    @Select("SELECT min(run_date) FROM scan_retry_item WHERE pipeline='TREND_HISTORY' AND status='QUEUED'") LocalDate nextTrendDate();
    @Select("SELECT pipeline,status,count(*) FROM scan_retry_item WHERE run_date=#{date} GROUP BY pipeline,status ORDER BY pipeline,status") List<Map<String,Object>> summary(LocalDate date);
}
