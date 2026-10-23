package com.stocksentinel.monitoring;
import com.stocksentinel.trend.TrendService;import com.stocksentinel.report.ScreeningService;
import java.time.LocalDate;import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;import org.springframework.web.bind.annotation.*;import org.springframework.http.HttpStatus;import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api/v1/monitoring/coverage")
public class ScanCoverageController {
    private final ScanCoverageService coverage;private final ScanRecoveryService recovery;private final ObjectProvider<TrendService> trends;private final ScreeningService screening;
    public ScanCoverageController(ScanCoverageService coverage,ScanRecoveryService recovery,ObjectProvider<TrendService> trends,ScreeningService screening){this.coverage=coverage;this.recovery=recovery;this.trends=trends;this.screening=screening;}
    @GetMapping public Object summary(@RequestParam(required=false)LocalDate tradeDate){return coverage.summary(tradeDate);}
    @GetMapping("/stocks/{symbol}") public Object stock(@PathVariable String symbol,@RequestParam(required=false)LocalDate tradeDate){return coverage.stock(symbol,tradeDate);}
    @GetMapping("/failures") public Object failures(@RequestParam String pipeline,@RequestParam(required=false)LocalDate date,@RequestParam(defaultValue="50")int limit,@RequestParam(defaultValue="0")int offset){return coverage.failures(pipeline,date,limit,offset);}
    @PostMapping("/retries") public Object retry(@RequestBody ScanRecoveryService.Request r){if(!Boolean.FALSE.equals(r.dryRun()))return recovery.preview(r);if(r.pipeline()==ScanRecoveryService.Pipeline.SCREENING)return screening.retrySelected(r);var trend=trends.getIfAvailable();if(trend==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"TREND_DISABLED");return trend.retrySelected(r);}
    @GetMapping("/retries/{id}") public Object retryResult(@PathVariable String id){return recovery.result(id);}
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST) public Object invalid(){return Map.of("error","INVALID_COVERAGE_REQUEST");}
}
