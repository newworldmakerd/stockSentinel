package com.stocksentinel;

import com.stocksentinel.market.*;
import com.stocksentinel.market.MarketContracts.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.web.server.ResponseStatusException;

class MarketCollectionTest {
    @Test void tencentReplacementMustCoverEveryPreviousSourceDate() {
        var client=mock(MarketClient.class);var writer=mock(MarketWriter.class);var mapper=mock(MarketMapper.class);
        when(mapper.stockExists("600487")).thenReturn(1);
        var old=new com.stocksentinel.common.entity.MarketDailyBar();old.tradeDate=START.minusDays(1);old.source="OLD_SOURCE";
        when(mapper.sourceHistory("600487",Adjustment.QFQ)).thenReturn(List.of(old));
        var b=bar(START,true);var backup=new Bar(b.symbol(),b.tradeDate(),b.openPrice(),b.highPrice(),b.lowPrice(),b.closePrice(),b.volume(),b.amount(),b.turnoverRate(),b.adjustment(),"AKSHARE_TENCENT_DAILY",true);
        when(client.daily(anyString(),any(),any(),any())).thenReturn(new Daily("600487",START,END,Adjustment.QFQ,"SHARE","CNY",List.of(backup)));
        var service=new MarketCollectionService(client,writer,mapper,true);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.collect("600487",START,END,Adjustment.QFQ)).getStatusCode().value());verifyNoInteractions(writer);
        old.tradeDate=START;when(writer.bars(any())).thenReturn(1);
        assertEquals(1,service.collect("600487",START,END,Adjustment.QFQ).written());
    }
    @Test void returningPrimaryAlsoRequiresCompleteTencentHistoryCoverage() {
        var client=mock(MarketClient.class);var writer=mock(MarketWriter.class);var mapper=mock(MarketMapper.class);
        when(mapper.stockExists("600487")).thenReturn(1);
        var old=new com.stocksentinel.common.entity.MarketDailyBar();old.tradeDate=START.minusDays(1);old.source="AKSHARE_TENCENT_DAILY";
        when(mapper.sourceHistory("600487",Adjustment.QFQ)).thenReturn(List.of(old));
        when(client.daily(anyString(),any(),any(),any())).thenReturn(new Daily("600487",START,END,Adjustment.QFQ,"SHARE","CNY",List.of(bar(START,true))));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->new MarketCollectionService(client,writer,mapper,true).collect("600487",START,END,Adjustment.QFQ)).getStatusCode().value());
        verifyNoInteractions(writer);
    }
    private static final LocalDate START=LocalDate.of(2025,1,1), END=LocalDate.of(2025,12,31);
    private Bar bar(LocalDate day, boolean finalized) {
        return new Bar("600487",day,new BigDecimal("10"),new BigDecimal("12"),new BigDecimal("9"),
            new BigDecimal("11"),new BigDecimal("12300"),new BigDecimal("135300"),new BigDecimal("0.0125"),
            Adjustment.QFQ,"fixture",finalized);
    }
    @Test void disabledDoesNotTouchDependencies() {
        var client=mock(MarketClient.class); var writer=mock(MarketWriter.class); var mapper=mock(MarketMapper.class);
        var service=new MarketCollectionService(client,writer,mapper,false);
        assertThrows(ResponseStatusException.class,service::syncStocks);
        verifyNoInteractions(client,writer,mapper);
    }
    @Test void validatesWholeResponseBeforeWriting() {
        var client=mock(MarketClient.class); var writer=mock(MarketWriter.class); var mapper=mock(MarketMapper.class);
        when(mapper.stockExists("600487")).thenReturn(1);
        var duplicate=bar(START,true);
        when(client.daily(anyString(),any(),any(),any())).thenReturn(new Daily("600487",START,END,Adjustment.QFQ,"SHARE","CNY",List.of(duplicate,duplicate)));
        var service=new MarketCollectionService(client,writer,mapper,true);
        assertEquals(502,assertThrows(ResponseStatusException.class,()->service.collect("600487",START,END,Adjustment.QFQ)).getStatusCode().value());
        verifyNoInteractions(writer);
    }
    @Test void imports120FinalRowsAndSkipsUnfinishedRow() {
        var client=mock(MarketClient.class); var writer=mock(MarketWriter.class); var mapper=mock(MarketMapper.class);
        when(mapper.stockExists("600487")).thenReturn(1);
        var rows=new java.util.ArrayList<>(IntStream.range(0,120).mapToObj(i->bar(START.plusDays(i),true)).toList());
        rows.add(bar(END,false));
        when(client.daily(anyString(),any(),any(),any())).thenReturn(new Daily("600487",START,END,Adjustment.QFQ,"SHARE","CNY",rows));
        when(writer.bars(anyList())).thenAnswer(call->((List<?>)call.getArgument(0)).size());
        var result=new MarketCollectionService(client,writer,mapper,true).collect("600487",START,END,Adjustment.QFQ);
        assertEquals(new ImportResult(121,120,1,true),result);
        verify(writer).bars(argThat(values->values.size()==120 && values.stream().allMatch(Bar::isFinal)));
    }
    @Test void missingStockDoesNotCallPython() {
        var client=mock(MarketClient.class);
        var service=new MarketCollectionService(client,mock(MarketWriter.class),mock(MarketMapper.class),true);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.collect("600487",START,END,Adjustment.QFQ)).getStatusCode().value());
        verifyNoInteractions(client);
    }
    @Test void writerSplitsBatchesAt500() {
        var mapper=mock(MarketMapper.class);
        when(mapper.upsertBars(anyList())).thenAnswer(call->((List<?>)call.getArgument(0)).size());
        var rows=IntStream.range(0,1201).mapToObj(i->bar(START.plusDays(i),true)).toList();
        assertEquals(1201,new MarketWriter(mapper).bars(rows));
        verify(mapper,times(2)).upsertBars(argThat(values->values.size()==500));
        verify(mapper).upsertBars(argThat(values->values.size()==201));
    }
}
