package com.stocksentinel;

import com.stocksentinel.strategy.engine.*;
import com.stocksentinel.strategy.StrategyParameters;
import com.stocksentinel.market.MarketContracts.Adjustment;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.stocksentinel.StrategyFixtures.*;

class SwingTrendStrategyTest {
    private final SwingTrendStrategy engine=new SwingTrendStrategy();
    private boolean matched(StrategyContext context,SwingTrendStrategy.SignalType type) {
        return engine.evaluate(context).stream().filter(result->result.signalType()==type).findFirst().orElseThrow().matched();
    }
    @Test void buyRequiresAllFourRulesAndIsDeterministic() {
        var context=buy(); var first=engine.evaluate(context);
        assertTrue(matched(context,SwingTrendStrategy.SignalType.BUY_WATCH));
        assertEquals(4,first.getFirst().rules().size());
        assertFalse(matched(context,SwingTrendStrategy.SignalType.SELL_WATCH));
        assertEquals(first,engine.evaluate(context));
    }
    @Test void goldenCrossAllowsPreviousEqualityButNotCurrentEquality() {
        var context=buy(); context.previous().dif=context.previous().dea;
        assertTrue(new MacdCrossRule(true).evaluate(context).passed());
        context.current().dif=context.current().dea;
        assertFalse(new MacdCrossRule(true).evaluate(context).passed());
    }
    @Test void priceAndMaTrendUseStrictInequalities() {
        var context=buy(); context.currentBar().closePrice=context.current().ma20;
        assertFalse(matched(context,SwingTrendStrategy.SignalType.BUY_WATCH));
        context=buy(); context.current().ma20=context.previous().ma20;
        assertFalse(matched(context,SwingTrendStrategy.SignalType.BUY_WATCH));
    }
    @Test void volumeThresholdIncludesEqualityButDoesNotUseRoundedRatio() {
        var context=buy(); assertTrue(new VolumeConfirmationRule().evaluate(context).passed());
        context.currentBar().volume=number("119.99996");
        context.current().volumeRatio=number("1.200000");
        assertFalse(new VolumeConfirmationRule().evaluate(context).passed());
        assertFalse(matched(context,SwingTrendStrategy.SignalType.BUY_WATCH));
    }
    @Test void zeroBaselineCannotConfirmBuyVolume() {
        var original=buy(); var context=new StrategyContext(original.currentBar(),original.previousBar(),original.referenceBar(),
            original.current(),original.previous(),number("0"),original.parameters());
        assertFalse(new VolumeConfirmationRule().evaluate(context).passed());
    }
    @Test void sellRequiresDeadCrossAndBelowMa20() {
        var context=buy(); context.previous().dif=number("2"); context.previous().dea=number("1");
        context.current().dif=number("0"); context.current().dea=number("1");
        context.currentBar().closePrice=number("100");
        assertTrue(matched(context,SwingTrendStrategy.SignalType.SELL_WATCH));
        context.currentBar().closePrice=context.current().ma20;
        assertFalse(matched(context,SwingTrendStrategy.SignalType.SELL_WATCH));
    }
    @Test void riskRequiresDownwardCrossAndDoesNotRepeatWhileBelow() {
        var context=buy(); context.previousBar().closePrice=number("90"); context.currentBar().closePrice=number("89");
        assertTrue(matched(context,SwingTrendStrategy.SignalType.RISK_ALERT));
        context.previousBar().closePrice=number("89");
        assertFalse(matched(context,SwingTrendStrategy.SignalType.RISK_ALERT));
        context.previousBar().closePrice=number("91"); context.currentBar().closePrice=number("90");
        assertFalse(matched(context,SwingTrendStrategy.SignalType.RISK_ALERT));
    }
    @Test void unsupportedRiskOrSellModesAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new StrategyParameters(number("1.2"),Adjustment.QFQ,"ma-macd-vol-v1","BELOW_MA60","DEAD_CROSS_BELOW_MA20"));
        assertThrows(IllegalArgumentException.class,()->new StrategyParameters(number("1.2"),Adjustment.QFQ,"ma-macd-vol-v1","CROSS_BELOW_MA60","OTHER_COMBO"));
    }
}