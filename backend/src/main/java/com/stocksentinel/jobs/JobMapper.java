package com.stocksentinel.jobs;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import com.stocksentinel.common.entity.MarketCalendar;
import com.stocksentinel.common.entity.StockInfo;
import org.apache.ibatis.annotations.*;

@Mapper
public interface JobMapper {
    @Select("SELECT * FROM market_calendar WHERE trade_date=#{date}") MarketCalendar calendar(LocalDate date);
    @Insert("INSERT INTO market_calendar(trade_date,exchange,is_open,source) VALUES(#{date},'CN',#{open},#{source}) ON CONFLICT(trade_date) DO NOTHING")
    int calendarInsert(@Param("date") LocalDate date,@Param("open") boolean open,@Param("source") String source);
    @Select("SELECT * FROM market_calendar WHERE trade_date BETWEEN #{from} AND #{to} ORDER BY trade_date")
    List<MarketCalendar> calendars(@Param("from") LocalDate from,@Param("to") LocalDate to);
    @Select("SELECT count(*) FROM market_close_confirmation WHERE trade_date=#{date}") int anyConfirmation(LocalDate date);
    @Select("SELECT count(*) FROM market_close_confirmation WHERE trade_date=#{date} AND source=#{source}")
    int confirmed(@Param("date") LocalDate date,@Param("source") String source);
    @Insert("INSERT INTO market_close_confirmation(trade_date,source) VALUES(#{date},#{source}) ON CONFLICT DO NOTHING")
    int confirm(@Param("date") LocalDate date,@Param("source") String source);
    @Select("SELECT trade_date,source,confirmed_at FROM market_close_confirmation WHERE trade_date=#{date} ORDER BY source")
    List<Map<String,Object>> confirmations(LocalDate date);
    @Select("SELECT jsonb_build_object('tradeDate',trade_date,'source',source,'confirmedAt',confirmed_at)::text FROM market_close_confirmation WHERE trade_date=#{date} AND source=#{source}")
    String confirmationEvidence(@Param("date") LocalDate date,@Param("source") String source);
    @Select("SELECT s.* FROM stock_info s JOIN stock_watchlist w ON w.symbol=s.symbol WHERE w.enabled=true ORDER BY s.symbol")
    List<StockInfo> watchlist();
    @Select("SELECT min(trade_date) FROM market_daily_bar WHERE symbol=#{symbol} AND adjustment=#{adjustment}")
    LocalDate firstDate(@Param("symbol") String symbol,@Param("adjustment") String adjustment);
    @Insert("INSERT INTO job_execution(job_name,status,trade_date,trigger_type) VALUES('DAILY_SCAN','RUNNING',#{date},#{trigger})")
    @Options(useGeneratedKeys=true,keyProperty="id",keyColumn="id")
    int start(Execution job);
    @Update("UPDATE job_execution SET status='FAILED',finished_at=now(),error_message='PREVIOUS_PROCESS_INTERRUPTED' WHERE job_name='DAILY_SCAN' AND status='RUNNING' AND id != #{id}")
    int recover(long id);
    @Update("UPDATE job_execution SET status=#{status},finished_at=now(),processed_count=#{processed},success_count=#{success},failed_count=#{failed},skipped_count=#{skipped},error_message=#{code} WHERE id=#{id}")
    int finish(@Param("id") long id,@Param("status") String status,@Param("processed") int processed,@Param("success") int success,
        @Param("failed") int failed,@Param("skipped") int skipped,@Param("code") String code);
    @Insert("INSERT INTO job_execution_item(job_id,symbol,stage,status,code) VALUES(#{job},#{symbol},#{stage},#{status},#{code})")
    int item(@Param("job") long job,@Param("symbol") String symbol,@Param("stage") String stage,@Param("status") String status,@Param("code") String code);
    @Select("SELECT * FROM job_execution ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}")
    List<Map<String,Object>> jobs(@Param("limit") int limit,@Param("offset") int offset);
    @Select("SELECT * FROM job_execution WHERE id=#{id}") Map<String,Object> job(long id);
    @Select("SELECT * FROM job_execution_item WHERE job_id=#{id} ORDER BY id") List<Map<String,Object>> items(long id);
    class Execution { public Long id;public LocalDate date;public String trigger; }
}
