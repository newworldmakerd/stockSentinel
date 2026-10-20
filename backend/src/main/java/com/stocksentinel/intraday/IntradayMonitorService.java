package com.stocksentinel.intraday;

import com.stocksentinel.intraday.IntradayContracts.*;
import com.stocksentinel.stock.WatchlistMapper;
import com.stocksentinel.stock.WatchlistService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;

@Service
public class IntradayMonitorService {
    private final IntradayMapper mapper;private final WatchlistMapper stocks;
    public IntradayMonitorService(IntradayMapper mapper,WatchlistMapper stocks) {this.mapper=mapper;this.stocks=stocks;}
    @Transactional public Config configure(String symbol,Parameters p) {
        WatchlistService.symbol(symbol);var stock=stocks.stock(symbol);
        if(stock==null) throw error(HttpStatus.NOT_FOUND,"STOCK_NOT_FOUND");
        if(!java.util.Set.of("SH","SZ").contains(stock.exchange)) throw error(HttpStatus.BAD_REQUEST,"UNSUPPORTED_INTRADAY_EXCHANGE");
        if(stocks.find(symbol)==null) throw error(HttpStatus.CONFLICT,"ADD_WATCHLIST_FIRST");
        if(p==null || p.enabled()==null || !bounded(p.rapidPct(),"30") || !bounded(p.volumeMultiplier(),"100")
                || p.cooldownMinutes()==null || p.cooldownMinutes()<1 || p.cooldownMinutes()>1440
                || (p.takeProfit()!=null && !IntradayValidation.price(p.takeProfit())) || (p.stopLoss()!=null && !IntradayValidation.price(p.stopLoss()))
                || (p.takeProfit()!=null && p.stopLoss()!=null && p.stopLoss().compareTo(p.takeProfit())>=0)) throw error(HttpStatus.BAD_REQUEST,"INVALID_INTRADAY_PARAMETERS");
        mapper.configure(symbol,p);return mapper.config(symbol);
    }
    private boolean bounded(BigDecimal n,String max) {return n!=null && n.signum()>0 && n.compareTo(new BigDecimal(max))<=0 && n.scale()<=4;}
    private ResponseStatusException error(HttpStatus status,String code) {return new ResponseStatusException(status,code);}
}
