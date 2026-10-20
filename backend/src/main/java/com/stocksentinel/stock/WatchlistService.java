package com.stocksentinel.stock;

import com.stocksentinel.common.entity.StockWatchlist;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WatchlistService {
    private final WatchlistMapper mapper;
    public WatchlistService(WatchlistMapper mapper) { this.mapper=mapper; }
    public static void symbol(String value) {
        if (value==null || !value.matches("[0-9]{6}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_SYMBOL");
    }
    private void remark(String value) {
        if (value!=null && value.length()>500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"REMARK_TOO_LONG");
    }
    @Transactional public StockWatchlist add(String symbol,String remark) {
        symbol(symbol); remark(remark);
        if (mapper.stock(symbol)==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"STOCK_NOT_FOUND");
        mapper.add(symbol,remark);
        return mapper.find(symbol);
    }
    @Transactional public StockWatchlist update(String symbol,Boolean enabled,String remark) {
        symbol(symbol); remark(remark);
        if (enabled==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"ENABLED_REQUIRED");
        if (mapper.update(symbol,enabled,remark)==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"WATCHLIST_NOT_FOUND");
        return mapper.find(symbol);
    }
    @Transactional public void delete(String symbol) { symbol(symbol); mapper.delete(symbol); }
}