package com.stocksentinel.stock;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class WatchlistController {
    public record Add(String symbol,String remark) {}
    public record Update(Boolean enabled,String remark) {}
    private final WatchlistMapper mapper;
    private final WatchlistService service;
    public WatchlistController(WatchlistMapper mapper,WatchlistService service) { this.mapper=mapper; this.service=service; }
    @GetMapping("/stocks") public Object stocks(@RequestParam(defaultValue="") String query,
            @RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="0") int offset) {
        if (query.length()>100 || limit<1 || limit>200 || offset<0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_SEARCH");
        return mapper.search(query,limit,offset);
    }
    @GetMapping("/watchlist") public Object list() { return mapper.list(); }
    @PostMapping("/watchlist") public Object add(@RequestBody Add request) { return service.add(request.symbol(),request.remark()); }
    @PutMapping("/watchlist/{symbol}") public Object update(@PathVariable String symbol,@RequestBody Update request) {
        return service.update(symbol,request.enabled(),request.remark());
    }
    @DeleteMapping("/watchlist/{symbol}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String symbol) { service.delete(symbol); }
}