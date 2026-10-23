package com.stocksentinel.signal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stocksentinel.common.entity.SignalEvent;
import com.stocksentinel.stock.WatchlistService;
import com.stocksentinel.strategy.engine.SwingTrendStrategy.SignalType;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/signals")
public class SignalController {
    private final SignalMapper mapper;
    private final ObjectMapper json;
    public SignalController(SignalMapper mapper,ObjectMapper json) { this.mapper=mapper; this.json=json; }
    @GetMapping public Object list(@RequestParam(required=false) String symbol,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required=false) String type,@RequestParam(defaultValue="100") int limit,@RequestParam(defaultValue="0") int offset) {
        if (symbol!=null) WatchlistService.symbol(symbol);
        if(type!=null && !java.util.Set.of("PULLBACK_STRENGTH","BUY_WATCH","SELL_WATCH","RISK_ALERT","RAPID_RISE","RAPID_DROP","BREAKOUT_WATCH","TAKE_PROFIT","STOP_LOSS").contains(type))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_SIGNAL_TYPE");
        if (limit<1 || limit>200 || offset<0 || (from!=null && to!=null && from.isAfter(to)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_SIGNAL_QUERY");
        return mapper.list(symbol,from,to,type,limit,offset).stream().map(this::view).toList();
    }
    @GetMapping("/{id}") public Object detail(@PathVariable long id) {
        var signal=mapper.find(id);
        if (signal==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"SIGNAL_NOT_FOUND");
        return java.util.Map.of("signal",view(signal),"ruleHits",mapper.rulesFor(id));
    }
    private JsonNode view(SignalEvent signal) {
        var node=json.valueToTree(signal);
        try { ((com.fasterxml.jackson.databind.node.ObjectNode)node).set("details",json.readTree(signal.details)); }
        catch (Exception failed) { throw new IllegalStateException("Invalid stored signal snapshot",failed); }
        return node;
    }
}
