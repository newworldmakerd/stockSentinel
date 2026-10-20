package com.stocksentinel.intraday;

import com.stocksentinel.intraday.IntradayContracts.Parameters;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/intraday")
public class IntradayController {
    private final IntradayMapper mapper;private final IntradayMonitorService monitors;private final IntradayScanService scans;private final ObjectMapper json;
    public IntradayController(IntradayMapper mapper,IntradayMonitorService monitors,IntradayScanService scans,ObjectMapper json) {
        this.mapper=mapper;this.monitors=monitors;this.scans=scans;this.json=json;
    }
    @GetMapping("/monitors") public Object list() {return mapper.all();}
    @PutMapping("/monitors/{symbol}") public Object configure(@PathVariable String symbol,@RequestBody String body) {
        Parameters request;
        try {request=json.readerFor(Parameters.class).with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(body);}
        catch(Exception invalid) {throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_INTRADAY_PARAMETERS");}
        return monitors.configure(symbol,request);
    }
    @PostMapping("/scan") public Object scan() {return scans.scan("MANUAL");}
}
