package com.stocksentinel.document;

import org.apache.ibatis.annotations.*;
import java.time.LocalDate;
import java.util.*;

@Mapper
public interface DocumentArchiveMapper {
    class Target {public String status,docId,docUrl,sheetId,lastError;}
    class Export {public LocalDate tradeDate;public String status,lastError,records,docId,sheetId;public Integer totalRows,confirmedRows;}
    @Select("SELECT * FROM wecom_document_target WHERE target_key='daily-stocks'") Target target();
    @Update("UPDATE wecom_document_target SET status='SETTING_UP',last_error=NULL,updated_at=now() WHERE target_key='daily-stocks' AND status='NEW'") int beginSetup();
    @Update("UPDATE wecom_document_target SET doc_id=#{id},doc_url=#{url},updated_at=now() WHERE target_key='daily-stocks' AND status='SETTING_UP'")
    int doc(@Param("id") String id,@Param("url") String url);
    @Update("UPDATE wecom_document_target SET sheet_id=#{id},updated_at=now() WHERE target_key='daily-stocks' AND status='SETTING_UP'") int sheet(String id);
    @Update("UPDATE wecom_document_target SET status=#{status},last_error=#{code},updated_at=now() WHERE target_key='daily-stocks' AND status='SETTING_UP'")
    int setupResult(@Param("status") String status,@Param("code") String code);
    @Select("SELECT EXISTS(SELECT 1 FROM daily_group_report WHERE trade_date=#{date})") boolean reportExists(LocalDate date);
    @Select("""
        SELECT DISTINCT ON(s.symbol) s.symbol,i.name,s.signal_type,s.reference_price,s.reason,s.risk_note,s.signal_at,s.trade_date,
        a.advice::text AS advice FROM signal_event s JOIN stock_info i ON i.symbol=s.symbol
        LEFT JOIN LATERAL(SELECT advice FROM signal_advice WHERE signal_id=s.id AND status='SUCCESS' ORDER BY id DESC LIMIT 1) a ON true
        WHERE s.trade_date=#{date} ORDER BY s.symbol,s.id DESC
        """) List<Map<String,Object>> publicSignals(LocalDate date);
    @Select("SELECT trade_date,status,records::text AS records,doc_id,sheet_id,total_rows,confirmed_rows,last_error FROM wecom_document_export WHERE trade_date=#{date}") Export export(LocalDate date);
    @Insert("INSERT INTO wecom_document_export(trade_date,doc_id,sheet_id,records,total_rows) VALUES(#{date},#{doc},#{sheet},CAST(#{records} AS jsonb),#{total}) ON CONFLICT DO NOTHING")
    int freeze(@Param("date") LocalDate date,@Param("doc") String doc,@Param("sheet") String sheet,@Param("records") String records,@Param("total") int total);
    @Update("UPDATE wecom_document_export SET status='SENDING' WHERE trade_date=#{date} AND status='PENDING'") int claim(LocalDate date);
    @Update("UPDATE wecom_document_export SET status='PENDING',last_error=NULL,finished_at=NULL WHERE trade_date=#{date} AND status='FAILED'") int retryConfirmedFailure(LocalDate date);
    @Update("UPDATE wecom_document_export SET confirmed_rows=#{count} WHERE trade_date=#{date} AND status='SENDING'")
    int progress(@Param("date") LocalDate date,@Param("count") int count);
    @Update("UPDATE wecom_document_export SET status=#{status},last_error=#{code},finished_at=now() WHERE trade_date=#{date} AND status='SENDING'")
    int finish(@Param("date") LocalDate date,@Param("status") String status,@Param("code") String code);
}
