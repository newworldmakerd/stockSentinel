package com.stocksentinel.jobs;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class JobController {
    private final JobMapper mapper;
    private final TradingDatePolicy dates;
    private final DailyScanService jobs;
    public JobController(JobMapper mapper,TradingDatePolicy dates,DailyScanService jobs) { this.mapper=mapper;this.dates=dates;this.jobs=jobs; }
    public record Day(LocalDate tradeDate) {}
    public record Confirmation(LocalDate tradeDate,String source) {}
    @PostMapping("/calendar/import") public Object calendar(@RequestBody List<TradingDatePolicy.CalendarEntry> entries) {
        return Map.of("accepted",dates.importCalendar(entries));
    }
    @GetMapping("/calendar") public Object calendar(@RequestParam LocalDate from,@RequestParam LocalDate to) {
        if(from.isAfter(to) || java.time.temporal.ChronoUnit.DAYS.between(from,to)>1000) throw bad("INVALID_CALENDAR_RANGE");
        return mapper.calendars(from,to);
    }
    @PostMapping("/market/close-confirmations") public Object confirm(@RequestBody Confirmation request) {
        dates.confirm(request.tradeDate(),request.source());return Map.of("status","CONFIRMED");
    }
    @GetMapping("/market/close-confirmations") public Object confirmations(@RequestParam LocalDate tradeDate) {
        return mapper.confirmations(tradeDate);
    }
    @PostMapping("/jobs/daily-scan") public Object scan(@RequestBody Day request) { return jobs.run(request.tradeDate(),"MANUAL"); }
    @GetMapping("/jobs") public Object list(@RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="0") int offset) {
        if(limit<1 || limit>200 || offset<0) throw bad("INVALID_JOB_QUERY");return mapper.jobs(limit,offset);
    }
    @GetMapping("/jobs/{id}") public Object detail(@PathVariable long id) {
        var job=mapper.job(id);if(job==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"JOB_NOT_FOUND");
        return Map.of("job",job,"items",mapper.items(id));
    }
    private ResponseStatusException bad(String code) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,code); }
}
