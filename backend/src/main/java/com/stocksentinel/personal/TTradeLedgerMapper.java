package com.stocksentinel.personal;

import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface TTradeLedgerMapper {
    class Event {public Long id,rootId,quantity;public String messageHash,groupHash,userId,symbol,kind,side,origin;public LocalDate tradeDate;public BigDecimal price;public OffsetDateTime createdAt;}
    class Totals {public Long buyQuantity,sellQuantity;public BigDecimal buyAmount,sellAmount;public String firstSide;}
    @Insert("""
        INSERT INTO t_trade_event(root_id,message_hash,group_hash,user_id,symbol,trade_date,kind,side,quantity,price)
        VALUES(#{rootId},#{messageHash},#{groupHash},#{userId},#{symbol},#{tradeDate},#{kind},#{side},#{quantity},#{price})
        """) @Options(useGeneratedKeys=true,keyProperty="id") int insert(Event row);
    @Select("SELECT * FROM t_trade_event WHERE message_hash=#{hash}") Event byMessage(String hash);
    @Select("SELECT * FROM t_trade_event WHERE id=#{id} AND group_hash=#{group} AND user_id=#{user} AND kind='FILL'")
    Event root(@Param("id") long id,@Param("group") String group,@Param("user") String user);
    @Select("SELECT * FROM t_trade_event WHERE (id=#{root} OR root_id=#{root}) AND group_hash=#{group} AND user_id=#{user} ORDER BY id DESC LIMIT 1")
    Event latest(@Param("root") long root,@Param("group") String group,@Param("user") String user);
    @Select("""
        WITH latest AS (SELECT DISTINCT ON (COALESCE(root_id,id)) * FROM t_trade_event
          WHERE group_hash=#{group} AND user_id=#{user} AND symbol=#{symbol} AND trade_date=#{day}
          ORDER BY COALESCE(root_id,id),id DESC), effective AS (SELECT * FROM latest WHERE kind<>'VOID')
        SELECT COALESCE(sum(quantity) FILTER(WHERE side='BUY'),0) AS buy_quantity,
        COALESCE(sum(quantity) FILTER(WHERE side='SELL'),0) AS sell_quantity,
        COALESCE(sum(quantity*price) FILTER(WHERE side='BUY'),0) AS buy_amount,
        COALESCE(sum(quantity*price) FILTER(WHERE side='SELL'),0) AS sell_amount,
        (SELECT side FROM effective ORDER BY COALESCE(root_id,id) LIMIT 1) AS first_side FROM effective
        """) Totals totals(@Param("group") String group,@Param("user") String user,@Param("symbol") String symbol,@Param("day") LocalDate day);
    @Select("SELECT * FROM t_trade_event WHERE group_hash=#{group} AND user_id=#{user} AND symbol=#{symbol} AND trade_date=#{day} ORDER BY id DESC LIMIT 20 OFFSET #{offset}")
    List<Event> audit(@Param("group") String group,@Param("user") String user,@Param("symbol") String symbol,@Param("day") LocalDate day,@Param("offset") int offset);
    @Update("UPDATE t_observation_day SET position_revision=#{revision},last_code='BASELINE_RECONCILED',updated_at=now() WHERE group_hash=#{group} AND user_id=#{user} AND symbol=#{symbol} AND trade_date=#{day}")
    int reconcile(@Param("group") String group,@Param("user") String user,@Param("symbol") String symbol,@Param("day") LocalDate day,@Param("revision") long revision);
}
