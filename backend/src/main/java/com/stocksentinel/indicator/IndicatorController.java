package com.stocksentinel.indicator;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import com.stocksentinel.market.MarketContracts.Adjustment;

@RestController
@RequestMapping("/api/v1/indicators")
public class IndicatorController {
    private final IndicatorService service;
    private final IndicatorMapper mapper;
    public IndicatorController(IndicatorService service,IndicatorMapper mapper) { this.service=service; this.mapper=mapper; }
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<org.springframework.http.ProblemDetail> error(
            org.springframework.web.server.ResponseStatusException error) {
        var body=org.springframework.http.ProblemDetail.forStatusAndDetail(error.getStatusCode(),error.getReason());
        body.setProperty("code",error.getReason()==null ? "INDICATOR_ERROR" : error.getReason().split(":",2)[0]);
        return org.springframework.http.ResponseEntity.status(error.getStatusCode()).body(body);
    }
    @PostMapping("/{symbol}/calculate")
    public IndicatorContracts.Result calculate(@PathVariable String symbol,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(defaultValue="QFQ") Adjustment adjustment) {
        return service.calculate(symbol,adjustment,asOf);
    }
    @GetMapping("/{symbol}")
    public Object latest(@PathVariable String symbol,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(defaultValue="QFQ") Adjustment adjustment,
            @RequestParam(defaultValue=IndicatorContracts.VERSION) String calculationVersion) {
        IndicatorService.validate(symbol,adjustment,asOf);
        if (!calculationVersion.matches("[A-Za-z0-9_-]{1,30}"))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"INVALID_VERSION");
        return mapper.latest(symbol,adjustment,calculationVersion,asOf);
    }
}