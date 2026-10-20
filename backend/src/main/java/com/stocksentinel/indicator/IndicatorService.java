package com.stocksentinel.indicator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import com.stocksentinel.market.MarketContracts.*;
import static com.stocksentinel.indicator.IndicatorContracts.*;

@Service
public class IndicatorService {
    private final IndicatorMapper mapper;
    private final IndicatorClient client;
    private final IndicatorWriter writer;
    private final com.stocksentinel.jobs.TradingDatePolicy dates;
    public IndicatorService(IndicatorMapper mapper,IndicatorClient client,IndicatorWriter writer) {
        this(mapper,client,writer,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public IndicatorService(IndicatorMapper mapper,IndicatorClient client,IndicatorWriter writer,com.stocksentinel.jobs.TradingDatePolicy dates) {
        this.mapper=mapper; this.client=client; this.writer=writer;
        this.dates=dates;
    }
    public Result calculate(String symbol,Adjustment adjustment,LocalDate asOf) {
        validate(symbol,adjustment,asOf,dates!=null && dates.allowToday(asOf));
        var history=mapper.history(symbol,adjustment,asOf);
        if (history.size()<WARMUP) throw failure(HttpStatus.UNPROCESSABLE_ENTITY,"INSUFFICIENT_HISTORY: required=120, received="+history.size());
        if (history.size()>10000) throw failure(HttpStatus.UNPROCESSABLE_ENTITY,"HISTORY_LIMIT_EXCEEDED");
        List<Bar> bars;
        try {
            bars=history.stream().map(r->new Bar(r.symbol,r.tradeDate,r.openPrice,r.highPrice,r.lowPrice,
                r.closePrice,r.volume,r.amount,r.turnoverRate,Adjustment.valueOf(r.adjustment),r.source,true)).toList();
            String source=bars.getFirst().source();
            if(dates!=null && asOf.equals(dates.today()) && (!dates.confirmedSource(asOf,source)
                    || !bars.getLast().tradeDate().equals(asOf))) throw new IllegalArgumentException();
            for (int index=0;index<bars.size();index++) {
                var bar=bars.get(index);
                if (!bar.symbol().equals(symbol) || bar.adjustment()!=adjustment || !bar.source().equals(source)
                        || bar.tradeDate().isAfter(asOf) || (index>0 && !bar.tradeDate().isAfter(bars.get(index-1).tradeDate())))
                    throw new IllegalArgumentException();
            }
        } catch (Exception failure) { throw failure(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_HISTORY"); }
        Output output;
        try {
            output=client.calculate(new Input(symbol,adjustment,VERSION,bars,dates!=null && dates.allowToday(asOf)));
        } catch (RestClientResponseException rejected) {
            throw failure(rejected.getStatusCode().value()==422 ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.BAD_GATEWAY,
                rejected.getStatusCode().value()==422 ? "INDICATOR_INPUT_REJECTED" : "INDICATOR_SERVICE_FAILED");
        } catch (Exception failed) { throw failure(HttpStatus.BAD_GATEWAY,"INDICATOR_SERVICE_FAILED"); }
        if (output==null || !Objects.equals(symbol,output.symbol()) || output.adjustment()!=adjustment
                || !VERSION.equals(output.calculationVersion()) || output.historySize()!=bars.size()
                || output.warmupPeriod()!=WARMUP || output.indicators()==null
                || output.indicators().size()!=bars.size()-WARMUP+1)
            throw failure(HttpStatus.BAD_GATEWAY,"INVALID_INDICATOR_RESPONSE");
        for (int index=0;index<output.indicators().size();index++) {
            Row row=output.indicators().get(index);
            if (row==null || !Objects.equals(row.tradeDate(),bars.get(index+WARMUP-1).tradeDate())
                    || !positive(row.ma5()) || !positive(row.ma10()) || !positive(row.ma20()) || !positive(row.ma60())
                    || row.dif()==null || row.dea()==null || row.macdHist()==null
                    || (row.volumeRatio()!=null && row.volumeRatio().signum()<0))
                throw failure(HttpStatus.BAD_GATEWAY,"INVALID_INDICATOR_RESPONSE");
        }
        int written=writer.save(symbol,adjustment,VERSION,output.indicators());
        int missing=(int)output.indicators().stream().filter(row->row.volumeRatio()==null).count();
        return new Result(symbol,adjustment,VERSION,bars.size(),written,missing);
    }
    public static void validate(String symbol,Adjustment adjustment,LocalDate asOf) {
        validate(symbol,adjustment,asOf,false);
    }
    public static void validate(String symbol,Adjustment adjustment,LocalDate asOf,boolean todayConfirmed) {
        if (symbol==null || !symbol.matches("[0-9]{6}") || adjustment==null || asOf==null
                || (!asOf.isBefore(LocalDate.now(ZoneId.of("Asia/Shanghai")))
                    && !(todayConfirmed && asOf.equals(LocalDate.now(ZoneId.of("Asia/Shanghai"))))))
            throw failure(HttpStatus.BAD_REQUEST,"INVALID_SYMBOL_ADJUSTMENT_OR_AS_OF");
    }
    private static boolean positive(BigDecimal value) { return value!=null && value.signum()>0; }
    private static ResponseStatusException failure(HttpStatus status,String code) {
        return new ResponseStatusException(status,code);
    }
}
