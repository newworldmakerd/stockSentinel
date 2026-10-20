package com.stocksentinel;

import com.stocksentinel.indicator.*;
import com.stocksentinel.common.entity.MarketDailyBar;
import com.stocksentinel.market.MarketContracts.Adjustment;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.web.server.ResponseStatusException;
import static com.stocksentinel.indicator.IndicatorContracts.*;

class IndicatorServiceTest {
    private final LocalDate asOf=LocalDate.of(2025,12,31);
    List<MarketDailyBar> history(int count) {
        var rows=new ArrayList<MarketDailyBar>();
        for (int i=0;i<count;i++) {
            var row=new MarketDailyBar();
            row.symbol="999998"; row.tradeDate=LocalDate.of(2025,1,1).plusDays(i);
            row.openPrice=row.closePrice=BigDecimal.valueOf(100+i);
            row.highPrice=row.closePrice.add(BigDecimal.ONE); row.lowPrice=row.closePrice.subtract(BigDecimal.ONE);
            row.volume=BigDecimal.valueOf(1000); row.adjustment="QFQ"; row.source="fixture";
            rows.add(row);
        }
        return rows;
    }
    @Test void insufficientHistoryDoesNotCallPythonOrWrite() {
        var mapper=mock(IndicatorMapper.class); var client=mock(IndicatorClient.class); var writer=mock(IndicatorWriter.class);
        when(mapper.history(anyString(),any(),any())).thenReturn(history(119));
        var service=new IndicatorService(mapper,client,writer);
        assertEquals(422,assertThrows(ResponseStatusException.class,()->service.calculate("999998",Adjustment.QFQ,asOf)).getStatusCode().value());
        verifyNoInteractions(client,writer);
    }
    @Test void wrongVersionIsRejectedBeforeWrite() {
        var mapper=mock(IndicatorMapper.class); var client=mock(IndicatorClient.class); var writer=mock(IndicatorWriter.class);
        when(mapper.history(anyString(),any(),any())).thenReturn(history(120));
        when(client.calculate(any())).thenReturn(new Output("999998",Adjustment.QFQ,"other",120,120,List.of()));
        assertEquals(502,assertThrows(ResponseStatusException.class,()->new IndicatorService(mapper,client,writer).calculate("999998",Adjustment.QFQ,asOf)).getStatusCode().value());
        verifyNoInteractions(writer);
    }
    @Test void validResponseIsWrittenWithVersion() {
        var mapper=mock(IndicatorMapper.class); var client=mock(IndicatorClient.class); var writer=mock(IndicatorWriter.class);
        var history=history(120);
        when(mapper.history(anyString(),any(),any())).thenReturn(history);
        var row=new Row(history.getLast().tradeDate,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,
            BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,null);
        when(client.calculate(any())).thenReturn(new Output("999998",Adjustment.QFQ,VERSION,120,120,List.of(row)));
        when(writer.save(anyString(),any(),anyString(),anyList())).thenReturn(1);
        var result=new IndicatorService(mapper,client,writer).calculate("999998",Adjustment.QFQ,asOf);
        assertEquals(new Result("999998",Adjustment.QFQ,VERSION,120,1,1),result);
        verify(client).calculate(argThat(input->input.bars().size()==120 && input.calculationVersion().equals(VERSION)));
    }
    @Test void badSourceHistoryDoesNotCallPython() {
        var mapper=mock(IndicatorMapper.class); var client=mock(IndicatorClient.class); var writer=mock(IndicatorWriter.class);
        var rows=history(120); rows.getLast().source="other";
        when(mapper.history(anyString(),any(),any())).thenReturn(rows);
        assertEquals(422,assertThrows(ResponseStatusException.class,()->new IndicatorService(mapper,client,writer).calculate("999998",Adjustment.QFQ,asOf)).getStatusCode().value());
        verifyNoInteractions(client,writer);
    }
    @Test void currentDateCannotBeUsedAsFinalHistory() {
        assertThrows(ResponseStatusException.class,()->IndicatorService.validate("999998",Adjustment.QFQ,LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))));
    }
    @Test void writerBatchesAndParsesSql() {
        var mapper=mock(IndicatorMapper.class);
        when(mapper.upsert(anyString(),any(),anyString(),anyList())).thenAnswer(call->((List<?>)call.getArgument(3)).size());
        var row=new Row(asOf,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,null);
        assertEquals(1001,new IndicatorWriter(mapper).save("999998",Adjustment.QFQ,VERSION,java.util.Collections.nCopies(1001,row)));
        verify(mapper,times(2)).upsert(eq("999998"),eq(Adjustment.QFQ),eq(VERSION),argThat(rows->rows.size()==500));
        var config=new com.baomidou.mybatisplus.core.MybatisConfiguration();
        config.addMapper(IndicatorMapper.class);
        String sql=config.getMappedStatement(IndicatorMapper.class.getName()+".upsert").getBoundSql(java.util.Map.of(
            "symbol","999998","adjustment",Adjustment.QFQ,"version",VERSION,"rows",List.of(row))).getSql();
        assertTrue(sql.contains("ON CONFLICT(symbol,trade_date,calculation_version,adjustment)"));
    }
}