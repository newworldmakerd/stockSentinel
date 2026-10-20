package com.stocksentinel.commands;

import org.apache.ibatis.annotations.*;

@Mapper
public interface CommandMapper {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{symbol},766))") Object lockSymbol(String symbol);
    @Insert("INSERT INTO wecom_monitor_command(command_key,status) VALUES(#{key},'RECEIVED') ON CONFLICT DO NOTHING") int claim(String key);
    @Update("UPDATE wecom_monitor_command SET status=#{status},stock_symbol=#{symbol},finished_at=now() WHERE command_key=#{key}")
    int finish(@Param("key") String key,@Param("status") String status,@Param("symbol") String symbol);
    @Update("UPDATE wecom_monitor_command SET ack_status=#{status} WHERE command_key=#{key}")
    int ack(@Param("key") String key,@Param("status") String status);
}
