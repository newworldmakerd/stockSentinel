package com.stocksentinel.report;

import com.fasterxml.jackson.databind.*;
import com.stocksentinel.common.entity.StockInfo;
import com.stocksentinel.market.*;
import com.stocksentinel.market.MarketContracts.Adjustment;
import com.stocksentinel.indicator.*;
import com.stocksentinel.strategy.*;
import com.stocksentinel.strategy.engine.*;
import com.stocksentinel.jobs.TradingDatePolicy;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.beans.factory.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

@Service
public class ScreeningService implements DisposableBean {
    private final ReportMapper mapper;private final MarketCollectionService market;private final IndicatorService indicators;
    private final StrategyContextReader contexts;private final SwingTrendStrategy engine;private final StrategyMapper strategies;
    private final TradingDatePolicy dates;private final Clock clock;private final ObjectMapper json;
    private final ObjectProvider<DataSource> sources;private final ObjectProvider<ScreenModel> models;
    private final RestClient http;private final boolean enabled;
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"market-screening");t.setDaemon(true);return t;});
    private final ExecutorService overviewExecutor=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"market-overview");t.setDaemon(true);return t;});
    private final AtomicBoolean active=new AtomicBoolean(),overviewActive=new AtomicBoolean();
    public ScreeningService(ReportMapper mapper,MarketCollectionService market,IndicatorService indicators,StrategyContextReader contexts,
            SwingTrendStrategy engine,StrategyMapper strategies,TradingDatePolicy dates,Clock clock,ObjectMapper json,
            ObjectProvider<DataSource> sources,ObjectProvider<ScreenModel> models,
            @Value("${market.python-url:http://127.0.0.1:8000}") String url,@Value("${screening.enabled:false}") boolean enabled) {
        this.mapper=mapper;this.market=market;this.indicators=indicators;this.contexts=contexts;this.engine=engine;this.strategies=strategies;
        this.dates=dates;this.clock=clock;this.json=json;this.sources=sources;this.models=models;this.enabled=enabled;
        var f=new SimpleClientHttpRequestFactory();f.setConnectTimeout(Duration.ofSeconds(3));f.setReadTimeout(Duration.ofMinutes(10));
        this.http=RestClient.builder().baseUrl(url).requestFactory(f).build();
    }
    public boolean start() {
        if(!enabled || !"OPEN".equals(dates.calendarStatus(dates.today())) || !active.compareAndSet(false,true)) return false;
        var existing=mapper.run(dates.today());
        if(existing!=null && !"RUNNING".equals(existing.status)) {active.set(false);return false;}
        executor.submit(()->{try {scan();} finally {active.set(false);}});return true;
    }
    public void refreshOverview() {
        if(!enabled || !overviewActive.compareAndSet(false,true)) return;
        overviewExecutor.submit(()->{try {
            var today=dates.today();if(mapper.run(today)!=null) storeOverview(today,mapper.universe());
        } catch(Exception unavailable) { /* Keep prior snapshot with its real timestamp. */ }
        finally {overviewActive.set(false);}});
    }
    void storeOverview(LocalDate date,List<StockInfo> universe) throws Exception {
        var keys=universe.stream().map(s->("SH".equals(s.exchange) ? "sh" : "sz")+s.symbol).toList();
        if(keys.isEmpty()) return;
        var response=http.post().uri("/internal/market/overview").body(Map.of("symbols",keys)).retrieve().body(JsonNode.class);
        if(response==null || !"TENCENT_QUOTE".equals(response.path("source").asText()) || !response.path("quotes").isArray()
                || !response.path("failed_symbols").isArray() || response.path("quotes").size()>universe.size()+3)
            throw new IllegalStateException("Invalid market overview");
        var at=OffsetDateTime.parse(response.path("observed_at").asText());var now=OffsetDateTime.now(clock);
        if(at.isAfter(now.plusSeconds(5)) || at.isBefore(now.minusMinutes(15))) throw new IllegalStateException("Stale overview");
        var expected=new HashSet<>(keys);expected.addAll(List.of("sh000001","sz399001","sz399006"));var found=new HashSet<String>();
        for(var q:response.path("quotes")) {
            String key=q.path("key").asText();var stamp=OffsetDateTime.parse(q.path("quoted_at").asText());
            if(!expected.contains(key) || !found.add(key) || !key.substring(2).equals(q.path("symbol").asText())
                    || q.path("name").asText().isBlank() || stamp.isAfter(now.plusSeconds(5))
                    || new BigDecimal(q.path("price").asText()).signum()<=0 || new BigDecimal(q.path("previous_close").asText()).signum()<=0
                    || new BigDecimal(q.path("volume").asText()).signum()<0) throw new IllegalStateException("Invalid overview quotes");
        }
        mapper.overview(date,json.writeValueAsString(response));
    }
    public void scan() {
        LocalDate today=dates.today();
        try(var connection=sources.getObject().getConnection()) {
            try(var s=connection.createStatement();var rs=s.executeQuery("SELECT pg_try_advisory_lock(76301,3)")) {rs.next();if(!rs.getBoolean(1)) return;}
            try {
                var existing=mapper.run(today);if(existing!=null && !"RUNNING".equals(existing.status)) return;
                LocalDate asOf=existing==null ? (dates.allowToday(today) ? today : mapper.previousOpen(today)) : existing.asOf;
                if(asOf==null) return;
                var universe=mapper.universe();mapper.start(today,asOf,universe.size());mapper.seed(today);
                try {storeOverview(today,universe);} catch(Exception unavailable) {mapper.finishRun(today,"OVERVIEW_UNAVAILABLE");return;}
                var run=mapper.run(today);var overview=json.readTree(run.overview);var quotes=new HashMap<String,JsonNode>();
                for(var q:overview.path("quotes")) quotes.put(q.path("key").asText(),q);
                var definition=strategies.find("SWING_TREND_V1");
                if(definition==null) throw new IllegalStateException("Missing screening strategy");
                if(!Boolean.TRUE.equals(definition.enabled)){mapper.finishRun(today,"STRATEGY_DISABLED");return;}
                var parameters=json.readValue(definition.parameters,StrategyParameters.class);
                int failures=0;
                for(var stock:mapper.pending(today)) {
                    if(Thread.currentThread().isInterrupted() || !today.equals(dates.today())) {mapper.finishRun(today,"INTERRUPTED");return;}
                    String key=("SH".equals(stock.exchange) ? "sh" : "sz")+stock.symbol;var quote=quotes.get(key);
                    if(quote==null || !OffsetDateTime.parse(quote.path("quoted_at").asText()).atZoneSameInstant(TradingDatePolicy.ZONE).toLocalDate().equals(today)
                            || new BigDecimal(quote.path("volume").asText()).signum()==0) {item(today,stock,"EXCLUDED","NO_CURRENT_TRADES",null,null);continue;}
                    if(quote.path("name").asText().toUpperCase(Locale.ROOT).contains("ST")) {item(today,stock,"EXCLUDED","ST",null,null);continue;}
                    try {
                        if(mapper.history(stock.symbol,asOf)<121 || !asOf.equals(latestDate(stock.symbol,asOf)))
                            market.collect(stock.symbol,asOf.minusYears(2),asOf,Adjustment.NONE);
                        if(mapper.history(stock.symbol,asOf)<121) {item(today,stock,"EXCLUDED","HISTORY_UNDER_121",null,null);continue;}
                        BigDecimal average=mapper.average(stock.symbol,asOf);
                        if(average==null || average.compareTo(new BigDecimal("100000000"))<0) {item(today,stock,"EXCLUDED",average==null ? "AMOUNT_MISSING" : "LOW_LIQUIDITY",average,null);continue;}
                        var state=strategies.state(stock.symbol,Adjustment.QFQ,asOf);
                        if(state.total<121 || strategies.recent(stock.symbol,Adjustment.QFQ,asOf).stream().noneMatch(b->asOf.equals(b.tradeDate)))
                            market.collect(stock.symbol,asOf.minusYears(2),asOf,Adjustment.QFQ);
                        indicators.calculate(stock.symbol,Adjustment.QFQ,asOf);
                        var prepared=contexts.read(stock.symbol,asOf,parameters);
                        var decision=engine.evaluate(prepared.context()).stream().filter(d->d.signalType()==SwingTrendStrategy.SignalType.BUY_WATCH).findFirst().orElseThrow();
                        String details=json.writeValueAsString(Map.of("asOf",asOf,"signalType",decision.signalType().name(),"strategyVersion",definition.version,"context",prepared.context(),"rules",decision.rules(),"average20Amount",average));
                        item(today,stock,decision.matched() ? "CANDIDATE" : "NO_MATCH",decision.matched() ? "BUY_WATCH" : "RULE_NOT_MATCHED",average,details);failures=0;
                    } catch(StrategyContextReader.Skipped insufficient) {item(today,stock,"EXCLUDED",insufficient.code,null,null);}
                    catch(Exception failed) {item(today,stock,"FAILED","HISTORY_OR_CALCULATION_UNAVAILABLE",null,null);if(++failures>=10) {mapper.finishRun(today,"SOURCE_UNAVAILABLE");return;}}
                }
                for(var candidate:mapper.candidates(today)) {
                    var model=models.getIfAvailable();if(model==null) break;
                    try {mapper.explanation(today,(String)candidate.get("symbol"),model.explain(json.writeValueAsString(candidate)));}
                    catch(Exception unavailable) { /* Rule-qualified candidate remains available, explanation explicitly absent. */ }
                }
                boolean failed=mapper.coverage(today).stream().anyMatch(r->"FAILED".equals(r.get("status")));
                mapper.finishRun(today,failed ? "PARTIAL" : "COMPLETED");
            } catch(Exception failure) {if(mapper.run(today)!=null) mapper.finishRun(today,"FAILED");}
            finally {try(var s=connection.createStatement()){s.execute("SELECT pg_advisory_unlock(76301,3)");}}
        } catch(Exception unavailable) { /* No raw network or credential exception logs. */ }
    }
    private LocalDate latestDate(String symbol,LocalDate asOf) {var b=strategies.reference(symbol,asOf);return b==null ? null : b.tradeDate;}
    private void item(LocalDate date,StockInfo stock,String status,String code,BigDecimal amount,String details) {mapper.item(date,stock.symbol,status,code,amount,details);}
    @Override public void destroy(){executor.shutdownNow();overviewExecutor.shutdownNow();}
}
