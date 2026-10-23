package com.stocksentinel.commands;

import org.apache.ibatis.annotations.*;

@Mapper
public interface CommandMapper {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{symbol},766))") Object lockSymbol(String symbol);
    @Update("UPDATE intraday_monitor SET enabled=false,revision=revision+1,updated_at=now() WHERE symbol=#{symbol} AND enabled") int disablePublicMonitor(String symbol);
    @Delete("DELETE FROM group_watch_owner WHERE symbol=#{symbol}") int removePublicOwners(String symbol);
    @Update("UPDATE notify_record n SET status='CANCELLED',last_error='WATCH_REMOVED',next_retry_at=NULL FROM signal_event s WHERE n.signal_id=s.id AND s.symbol=#{symbol} AND s.strategy_code IN ('SWING_TREND_V1','INTRADAY_V1','INTRADAY_MA60_V1') AND n.message_type<>'PERSONAL_POSITION' AND n.status IN ('PENDING','RETRY')") int cancelPendingPublic(String symbol);
    @Insert("INSERT INTO wecom_monitor_command(command_key,status) VALUES(#{key},'RECEIVED') ON CONFLICT DO NOTHING") int claim(String key);
    @Update("UPDATE wecom_monitor_command SET status=#{status},stock_symbol=#{symbol},finished_at=now() WHERE command_key=#{key}")
    int finish(@Param("key") String key,@Param("status") String status,@Param("symbol") String symbol);
    @Update("UPDATE wecom_monitor_command SET ack_status=#{status} WHERE command_key=#{key}")
    int ack(@Param("key") String key,@Param("status") String status);
}
