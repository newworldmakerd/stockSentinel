package com.stocksentinel.report;
import java.time.LocalDate;
import java.util.Map;
import com.stocksentinel.jobs.TradingDatePolicy;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class ReportController {
    private final DailyReportService reports;private final ScreeningService screening;private final ReportMapper mapper;private final TradingDatePolicy dates;
    public ReportController(DailyReportService reports,ScreeningService screening,ReportMapper mapper,TradingDatePolicy dates){this.reports=reports;this.screening=screening;this.mapper=mapper;this.dates=dates;}
    @GetMapping("/reports/daily/preview") public Object preview(@RequestParam LocalDate tradeDate){return Map.of("tradeDate",tradeDate,"markdown",reports.render(tradeDate));}
    @PostMapping("/reports/daily") public Object send(){var r=reports.enqueue(dates.today());return Map.of("id",r.id,"tradeDate",r.tradeDate,"status",r.status);}
    @GetMapping("/reports/daily") public Object report(@RequestParam LocalDate tradeDate){var r=mapper.report(tradeDate,reports.destinationHash());return r==null?Map.of("status","NOT_CREATED"):Map.of("id",r.id,"tradeDate",r.tradeDate,"status",r.status,"attempts",r.attempts,"markdown",r.payload);}
    @PostMapping("/screening/scan") public Object start(){return Map.of("accepted",screening.start());}
    @GetMapping("/screening") public Object screening(@RequestParam LocalDate tradeDate){var r=mapper.run(tradeDate);return r==null?Map.of("status","NOT_STARTED"):Map.of("tradeDate",r.tradeDate,"asOf",r.asOf,"status",r.status,"total",r.total,"coverage",mapper.coverage(tradeDate),"candidates",mapper.candidates(tradeDate));}
}
