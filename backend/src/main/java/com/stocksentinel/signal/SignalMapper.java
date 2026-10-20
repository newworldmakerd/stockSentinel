package com.stocksentinel.signal;

import com.stocksentinel.common.entity.SignalEvent;
import com.stocksentinel.common.entity.SignalRuleHit;
import com.stocksentinel.strategy.engine.RuleResult;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SignalMapper {
    @Insert("INSERT INTO signal_event(symbol,trade_date,strategy_code,strategy_version,signal_type,reference_price,reason,risk_note,details) VALUES(#{symbol},#{tradeDate},#{strategyCode},#{strategyVersion},#{signalType},#{referencePrice},#{reason},#{riskNote},CAST(#{details} AS jsonb)) ON CONFLICT(symbol,trade_date,strategy_code,strategy_version,signal_type) WHERE signal_at IS NULL DO NOTHING")
    @Options(useGeneratedKeys=true,keyProperty="id",keyColumn="id")
    int insert(SignalEvent signal);
    @Select("SELECT * FROM signal_event WHERE symbol=#{symbol} AND trade_date=#{date} AND strategy_code=#{code} AND strategy_version=#{version} AND signal_type=#{type}")
    SignalEvent existing(@Param("symbol") String symbol,@Param("date") LocalDate date,@Param("code") String code,
        @Param("version") String version,@Param("type") String type);
    @Insert("""
        <script>
        INSERT INTO signal_rule_hit(signal_id,rule_code,observed_value,threshold_value,passed,explanation) VALUES
        <foreach collection="rules" item="r" separator=",">(#{id},#{r.ruleCode},#{r.observedValue},#{r.thresholdValue},#{r.passed},#{r.explanation})</foreach>
        </script>
        """)
    int rules(@Param("id") long id,@Param("rules") List<RuleResult> rules);
    @Select("SELECT * FROM signal_event WHERE id=#{id}") SignalEvent find(long id);
    @Select("SELECT * FROM signal_rule_hit WHERE signal_id=#{id} ORDER BY id") List<SignalRuleHit> rulesFor(long id);
    @Select("""
        <script>SELECT * FROM signal_event WHERE 1=1
        <if test="symbol!=null">AND symbol=#{symbol}</if>
        <if test="from!=null">AND trade_date &gt;= #{from}</if>
        <if test="to!=null">AND trade_date &lt;= #{to}</if>
        <if test="type!=null">AND signal_type=#{type}</if>
        ORDER BY trade_date DESC,id DESC LIMIT #{limit} OFFSET #{offset}</script>
        """)
    List<SignalEvent> list(@Param("symbol") String symbol,@Param("from") LocalDate from,@Param("to") LocalDate to,
        @Param("type") String type,@Param("limit") int limit,@Param("offset") int offset);
}
