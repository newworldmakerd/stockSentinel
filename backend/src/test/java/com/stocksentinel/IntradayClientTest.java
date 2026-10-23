package com.stocksentinel;

import com.stocksentinel.intraday.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IntradayClientTest {
    @Test void rsiBudgetTimeoutIsBoundedUsingRealLocalHttp() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var release=new java.util.concurrent.CountDownLatch(1);
        server.createContext("/internal/indicators/t-rsi",exchange->{try{release.await(4,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{exchange.close();}});server.start();
        try{var client=new IntradayClient("http://127.0.0.1:"+server.getAddress().getPort(),45);long start=System.nanoTime();
            assertEquals("T_RSI_CONNECTION_OR_TIMEOUT",assertThrows(MinuteFetchException.class,()->client.tRsi("000731",1)).code);
            assertTrue(System.nanoTime()-start<java.util.concurrent.TimeUnit.SECONDS.toNanos(3));
        }finally{release.countDown();server.stop(0);}
    }
    @Test void tRsiContractUsesRealHttpAndClassifiesProviderError() throws Exception {
        var status=new java.util.concurrent.atomic.AtomicInteger(200);var seen=new java.util.concurrent.atomic.AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/indicators/t-rsi",exchange->{
            assertEquals("symbol=000731",exchange.getRequestURI().getQuery());seen.incrementAndGet();
            String json=status.get()==200?"""
                {"symbol":"000731","source":"AKSHARE_EASTMONEY_5M","adjustment":"QFQ","period":14,"observed_at":"2026-10-21T10:21:05+08:00","five_at":"2026-10-21T10:20:00+08:00","fifteen_at":"2026-10-21T10:15:00+08:00","rsi5":"81.1","rsi15":"51.2"}
                """:"{\"detail\":\"private upstream\"}";
            byte[] body=json.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(status.get(),body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try{var client=new IntradayClient("http://127.0.0.1:"+server.getAddress().getPort(),5);assertEquals(new java.math.BigDecimal("81.1"),client.tRsi("000731").rsi5());status.set(502);assertEquals("T_RSI_HTTP_502",assertThrows(MinuteFetchException.class,()->client.tRsi("000731")).code);assertEquals(2,seen.get());}finally{server.stop(0);}
    }
    @Test void failureCodesUseOnlyAllowlistedHeadersNeverResponseText() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/market/minutes",exchange->{
            exchange.getResponseHeaders().add("X-Minute-Primary-Error","DATA_TIME_FUTURE");
            exchange.getResponseHeaders().add("X-Minute-Backup-Error","DATA_OHLC");
            byte[] body="private upstream diagnostic".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(502,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            var client=new IntradayClient("http://127.0.0.1:"+server.getAddress().getPort(),5);
            var failure=assertThrows(MinuteFetchException.class,()->client.minutes("600487"));
            assertEquals("MINUTE_PRIMARY_DATA_TIME_FUTURE_BACKUP_DATA_OHLC",failure.code);
            assertFalse(failure.toString().contains("private"));
        } finally {server.stop(0);}
    }
    @Test void untrustedHeadersAndHtmlRemainGenericAndMalformedSuccessIsClassified() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var status=new java.util.concurrent.atomic.AtomicInteger(502);
        server.createContext("/internal/market/minutes",exchange->{
            exchange.getResponseHeaders().add("X-Minute-Primary-Error","private-error");
            exchange.getResponseHeaders().add("X-Minute-Backup-Error","TIMEOUT");
            exchange.getResponseHeaders().add("Content-Type","application/json");
            byte[] body="<html>private upstream diagnostic</html>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(),body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            var client=new IntradayClient("http://127.0.0.1:"+server.getAddress().getPort(),5);
            assertEquals("MINUTE_HTTP_502",assertThrows(MinuteFetchException.class,()->client.minutes("600487")).code);
            status.set(200);
            assertEquals("MINUTE_RESPONSE_INVALID",assertThrows(MinuteFetchException.class,()->client.minutes("600487")).code);
        } finally {server.stop(0);}
    }
    @Test void refusedConnectionIsReportedWithoutAnUnboundedRetry() throws Exception {
        int port;
        try(var socket=new java.net.ServerSocket(0)){port=socket.getLocalPort();}
        var client=new IntradayClient("http://127.0.0.1:"+port,5);
        assertEquals("MINUTE_HTTP_CONNECTION_FAILED",assertThrows(MinuteFetchException.class,()->client.minutes("600487")).code);
    }
    @Test void hangingHttpResponseHonorsReadTimeout() throws Exception {
        var release=new java.util.concurrent.CountDownLatch(1);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/market/minutes",exchange->{
            try {release.await(10,java.util.concurrent.TimeUnit.SECONDS);}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            finally {exchange.close();}
        });server.start();
        try {
            var client=new IntradayClient("http://127.0.0.1:"+server.getAddress().getPort(),5);
            assertEquals("MINUTE_HTTP_TIMEOUT",assertThrows(MinuteFetchException.class,()->client.minutes("600487")).code);
        } finally {release.countDown();server.stop(0);}
    }
}
