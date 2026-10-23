package com.stocksentinel.commands;

import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface GroupAssistantMapper {
    @Select("SELECT display_name FROM group_member_alias WHERE group_hash=#{group} AND user_id=#{user}")
    String displayName(@Param("group") String group,@Param("user") String user);
    @Insert("INSERT INTO group_watch_owner(group_hash,user_id,symbol) VALUES(#{group},#{user},#{symbol}) ON CONFLICT DO NOTHING")
    int follow(@Param("group") String group,@Param("user") String user,@Param("symbol") String symbol);
    @Insert("INSERT INTO group_member_alias(group_hash,user_id,display_name) VALUES(#{group},#{user},#{name}) ON CONFLICT(group_hash,user_id) DO UPDATE SET display_name=EXCLUDED.display_name,updated_at=now()")
    int alias(@Param("group") String group,@Param("user") String user,@Param("name") String name);
    @Select("""
        <script>SELECT w.symbol,s.name,w.enabled AS watch_enabled,m.enabled AS monitor_enabled,
        m.rapid_pct,m.volume_multiplier,m.cooldown_minutes,m.take_profit,m.stop_loss
        FROM stock_watchlist w JOIN stock_info s ON s.symbol=w.symbol LEFT JOIN intraday_monitor m ON m.symbol=w.symbol
        <if test="user!=null">WHERE EXISTS(SELECT 1 FROM group_watch_owner o WHERE o.symbol=w.symbol AND o.group_hash=#{group} AND o.user_id=#{user})</if>
        ORDER BY w.symbol LIMIT 20 OFFSET #{offset}</script>
        """)
    List<Map<String,Object>> watches(@Param("group") String group,@Param("user") String user,@Param("offset") int offset);
    @Select("SELECT o.user_id,a.display_name,o.created_at FROM group_watch_owner o LEFT JOIN group_member_alias a ON a.group_hash=o.group_hash AND a.user_id=o.user_id WHERE o.group_hash=#{group} AND o.symbol=#{symbol} ORDER BY o.created_at,o.user_id LIMIT 20")
    List<Map<String,Object>> owners(@Param("group") String group,@Param("symbol") String symbol);
}
