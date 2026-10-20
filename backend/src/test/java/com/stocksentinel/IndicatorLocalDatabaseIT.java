package com.stocksentinel;

import com.stocksentinel.indicator.*;
import com.stocksentinel.market.MarketWriter;
import com.stocksentinel.market.MarketContracts.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** Fixed, rolled-back fixtures; runs real Java -> local Python -> PostgreSQL without a market provider. */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named="STOCKSENTINEL_TEST_DB_URL",matches="jdbc:postgresql:.*")
class IndicatorLocalDatabaseIT {
    private static Process python;
    private static String url;
    private static synchronized String pythonUrl() {
        if (python!=null && python.isAlive()) return url;
        try {
            var root=Path.of("..").toAbsolutePath().normalize();
            Path executable=root.resolve(".venv/Scripts/python.exe");
            if (!java.nio.file.Files.exists(executable)) executable=root.resolve(".venv/bin/python");
            int port;
            try (var socket=new ServerSocket(0)) { port=socket.getLocalPort(); }
            url="http://127.0.0.1:"+port;
            var builder=new ProcessBuilder(executable.toString(),"-m","uvicorn","app.main:app",
                "--app-dir","quant-service","--host","127.0.0.1","--port",String.valueOf(port));
            builder.directory(root.toFile()).redirectErrorStream(true)
                .redirectOutput(root.resolve("task03-python-integration.log").toFile());
            builder.environment().keySet().removeIf(key->key.startsWith("POSTGRES_") || key.startsWith("STOCKSENTINEL_TEST_")
                || key.startsWith("LLM_") || key.startsWith("WECOM_") || key.equals("DB_URL"));
            builder.environment().put("MARKET_PROVIDER_ENABLED","false");
            python=builder.start();
            var http=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(1)).build();
            for (int attempt=0;attempt<100;attempt++) {
                if (!python.isAlive()) throw new IllegalStateException("Local Python exited; inspect integration log");
                try {
                    var request=HttpRequest.newBuilder(URI.create(url+"/health")).timeout(java.time.Duration.ofSeconds(1)).build();
                    if (http.send(request,HttpResponse.BodyHandlers.discarding()).statusCode()==200) return url;
                } catch (java.io.IOException ignored) { }
                Thread.sleep(100);
            }
            throw new IllegalStateException("Local Python readiness timeout");
        } catch (Exception failure) { stopPython(); throw new IllegalStateException("Could not start local compute service",failure); }
    }
    @AfterAll static void stopPython() {
        if (python!=null && python.isAlive()) {
            var descendants=python.descendants().toList();
            descendants.forEach(ProcessHandle::destroy);
            python.destroy();
            try {
                if (!python.waitFor(10,TimeUnit.SECONDS)) python.destroyForcibly();
                descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            } catch (InterruptedException interrupted) {
                descendants.forEach(ProcessHandle::destroyForcibly);
                python.destroyForcibly(); Thread.currentThread().interrupt();
            }
        }
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->System.getenv("STOCKSENTINEL_TEST_DB_URL"));
        registry.add("spring.datasource.username",()->System.getenv("STOCKSENTINEL_TEST_DB_USER"));
        registry.add("spring.datasource.password",()->System.getenv("STOCKSENTINEL_TEST_DB_PASSWORD"));
        registry.add("market.python-url",IndicatorLocalDatabaseIT::pythonUrl);
        registry.add("market.enabled",()->false);
        registry.add("llm.enabled",()->false);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired MarketWriter market;
    @Autowired IndicatorService service;
    @Autowired IndicatorMapper mapper;
    @Autowired IndicatorWriter writer;
    @BeforeEach void guardFixture() {
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM stock_info WHERE symbol='999998'",Integer.class),
            "Fixture symbol exists; refusing to modify existing data");
    }
    void seed(int count,Adjustment adjustment) {
        market.stocks(List.of(new Stock("999998","indicator fixture","SH")));
        market.bars(IntStream.range(0,count).mapToObj(index->{
            BigDecimal price=BigDecimal.valueOf(100+index);
            return new Bar("999998",LocalDate.of(2025,1,1).plusDays(index),price,price.add(BigDecimal.ONE),price.subtract(BigDecimal.ONE),
                price,BigDecimal.valueOf(1000+index*10),BigDecimal.valueOf(100000),BigDecimal.valueOf(.01),adjustment,"fixture",true);
        }).toList());
    }
    @Test void realPythonComputesAndWriterIsIdempotentAndIsolated() {
        seed(130,Adjustment.QFQ);
        var asOf=LocalDate.of(2025,12,31);
        var first=service.calculate("999998",Adjustment.QFQ,asOf);
        assertEquals(11,first.written());
        assertEquals(130,first.historySize());
        service.calculate("999998",Adjustment.QFQ,asOf);
        assertEquals(11,jdbc.queryForObject("SELECT count(*) FROM market_indicator_daily WHERE symbol='999998'",Integer.class));
        var latest=mapper.latest("999998",Adjustment.QFQ,IndicatorContracts.VERSION,asOf);
        assertEquals(2,latest.size());
        assertEquals(LocalDate.of(2025,1,1).plusDays(129),latest.getFirst().tradeDate);
        assertEquals(0,new BigDecimal("227").compareTo(latest.getFirst().ma5));
        assertEquals(0,new BigDecimal("199.5").compareTo(latest.getFirst().ma60));
        assertEquals(0,new BigDecimal("1.013274").compareTo(latest.getFirst().volumeRatio));
        double expectedDif=7-12.5*Math.pow(25.0/27,129)+5.5*Math.pow(11.0/13,129);
        assertEquals(expectedDif,latest.getFirst().dif.doubleValue(),1e-8);
        seed(130,Adjustment.NONE);
        service.calculate("999998",Adjustment.NONE,asOf);
        assertEquals(22,jdbc.queryForObject("SELECT count(*) FROM market_indicator_daily WHERE symbol='999998'",Integer.class));
        var existing=latest.getFirst();
        writer.save("999998",Adjustment.QFQ,"fixture-v0",List.of(new IndicatorContracts.Row(existing.tradeDate,
            existing.ma5,existing.ma10,existing.ma20,existing.ma60,existing.dif,existing.dea,existing.macdHist,existing.volumeRatio)));
        assertEquals(23,jdbc.queryForObject("SELECT count(*) FROM market_indicator_daily WHERE symbol='999998'",Integer.class));
        assertEquals(2,mapper.latest("999998",Adjustment.QFQ,IndicatorContracts.VERSION,asOf).size());
        assertEquals(1,mapper.latest("999998",Adjustment.QFQ,"fixture-v0",asOf).size());
    }
    @Test void insufficientHistoryDoesNotPersistIndicators() {
        seed(119,Adjustment.QFQ);
        var error=assertThrows(ResponseStatusException.class,()->service.calculate("999998",Adjustment.QFQ,LocalDate.of(2025,12,31)));
        assertEquals(422,error.getStatusCode().value());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM market_indicator_daily WHERE symbol='999998'",Integer.class));
    }
}