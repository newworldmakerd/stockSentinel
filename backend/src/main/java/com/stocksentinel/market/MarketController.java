package com.stocksentinel.market;

import java.time.LocalDate;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import com.stocksentinel.market.MarketContracts.*;

@RestController
@RequestMapping("/api/v1")
public class MarketController {
    private final MarketCollectionService collection;
    private final MarketMapper mapper;
    public MarketController(MarketCollectionService collection, MarketMapper mapper) {
        this.collection=collection; this.mapper=mapper;
    }
    @PostMapping("/stocks/sync")
    public Map<String,Integer> stocks() { return Map.of("written",collection.syncStocks()); }
    @PostMapping("/market/{symbol}/daily/import")
    public ImportResult collect(@PathVariable String symbol,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(defaultValue="NONE") Adjustment adjustment) {
        return collection.collect(symbol,start,end,adjustment);
    }
    @GetMapping("/market/{symbol}/daily")
    public Object daily(@PathVariable String symbol,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(defaultValue="NONE") Adjustment adjustment) {
        MarketCollectionService.validateRange(symbol,start,end);
        return mapper.daily(symbol,start,end,adjustment);
    }
}