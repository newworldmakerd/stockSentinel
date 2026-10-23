package com.stocksentinel.trend;
import java.time.LocalDate;import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;import org.springframework.web.bind.annotation.*;import org.springframework.http.HttpStatus;import org.springframework.web.server.ResponseStatusException;
@RestController @RequestMapping("/api/v1/trend")
public class TrendController {
    private final ObjectProvider<TrendService> services;
    public TrendController(ObjectProvider<TrendService> services){this.services=services;}
    private TrendService service(){var s=services.getIfAvailable();if(s==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"TREND_DISABLED");return s;}
    public record Prepare(LocalDate asOf,Boolean dryRun){} public record Scan(Boolean dryRun){}
    @GetMapping public Object status(){var s=services.getIfAvailable();return s==null?Map.of("enabled",false,"status","DISABLED"):s.status();}
    @PostMapping("/prepare") public Object prepare(@RequestBody Prepare r){return service().prepare(r.asOf(),!Boolean.FALSE.equals(r.dryRun()));}
    @PostMapping("/scan") public Object scan(@RequestBody(required=false)Scan r){if(r==null||!Boolean.FALSE.equals(r.dryRun()))return Map.of("dryRun",true);return service().scan(false);}
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST) public Object invalid(){return Map.of("error","INVALID_TREND_REQUEST");}
}
