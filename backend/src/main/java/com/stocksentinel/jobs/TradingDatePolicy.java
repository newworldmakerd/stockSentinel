package com.stocksentinel.jobs;

import java.time.*;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TradingDatePolicy {
    public static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private final JobMapper mapper;
    private final Clock clock;
    public TradingDatePolicy(JobMapper mapper,Clock clock) { this.mapper=mapper;this.clock=clock; }
    public LocalDate today() { return LocalDate.now(clock.withZone(ZONE)); }
    public boolean afterClose() { return !LocalTime.now(clock.withZone(ZONE)).isBefore(LocalTime.of(15,0)); }
    public String calendarStatus(LocalDate date) {
        var day=mapper.calendar(date);
        if(day==null || !"CN".equals(day.exchange)) return "CALENDAR_MISSING";
        return Boolean.TRUE.equals(day.isOpen) ? "OPEN" : "MARKET_CLOSED";
    }
    public boolean allowToday(LocalDate date) {
        return date!=null && date.equals(today()) && afterClose() && "OPEN".equals(calendarStatus(date)) && mapper.anyConfirmation(date)>0;
    }
    public boolean confirmedSource(LocalDate date,String source) {
        return allowToday(date) && mapper.confirmed(date,source)>0;
    }
    public String evidence(LocalDate date,String source) { return mapper.confirmationEvidence(date,source); }
    public record CalendarEntry(LocalDate tradeDate,Boolean isOpen,String source) {}
    @Transactional public int importCalendar(List<CalendarEntry> entries) {
        if(entries==null || entries.isEmpty() || entries.size()>1000) throw bad("INVALID_CALENDAR_IMPORT");
        var seen=new java.util.HashSet<LocalDate>();int count=0;
        for(var e:entries) {
            if(e==null || e.tradeDate()==null || e.isOpen()==null || !seen.add(e.tradeDate())
                    || e.tradeDate().isBefore(LocalDate.of(2000,1,1)) || e.tradeDate().isAfter(today().plusYears(2))) throw bad("INVALID_CALENDAR_ENTRY");
            source(e.source());mapper.calendarInsert(e.tradeDate(),e.isOpen(),e.source());
            var existing=mapper.calendar(e.tradeDate());
            if(!"CN".equals(existing.exchange) || !e.isOpen().equals(existing.isOpen) || !e.source().equals(existing.source))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"CALENDAR_ENTRY_IMMUTABLE");
            count++;
        }
        return count;
    }
    @Transactional public void confirm(LocalDate date,String source) {
        source(source);
        if(date==null || date.isAfter(today()) || (date.equals(today()) && !afterClose())) throw bad("CLOSE_CONFIRMATION_TOO_EARLY");
        if(!"OPEN".equals(calendarStatus(date))) throw bad("OPEN_CALENDAR_REQUIRED");
        mapper.confirm(date,source);
    }
    private void source(String source) {
        if(source==null || !source.matches("[A-Za-z0-9_:.\\-]{1,40}")) throw bad("INVALID_SOURCE");
    }
    private ResponseStatusException bad(String code) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,code); }
}
