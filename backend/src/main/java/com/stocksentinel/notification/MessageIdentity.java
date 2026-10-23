package com.stocksentinel.notification;

import com.stocksentinel.common.entity.SignalEvent;

/** Display labels only: never changes a signal, a rule or delivery routing. */
public final class MessageIdentity {
    private MessageIdentity() {}
    public enum Kind {
        CANDIDATE("🔎","选股候选"), INTRADAY("⚡","盘中异动"), RISK("🛡️","持仓风控"),
        T_OBSERVATION("🔄","做T观察"), TREND("📈","中长线趋势"),
        MARKET("🌐","大盘状态"), SYSTEM("🧰","系统状态"), REPORT("📊","每日汇总");
        final String icon,label;
        Kind(String icon,String label){this.icon=icon;this.label=label;}
    }
    public static String header(Kind kind,String action,String strategy,String scope,String phase) {
        return kind.icon+"【"+kind.label+"】"+action+"\n策略："+strategy+"｜"+scope+"｜"+phase+"\n";
    }
    public static String strategy(SignalEvent signal) {
        return switch(signal.strategyCode==null?"":signal.strategyCode) {
            case "SWING_TREND_V1" -> "日线波段";
            case "INTRADAY_MA60_V1" -> "盘中MA60预警";
            case "STRONG_PULLBACK_V1" -> "强势股缩量回踩";
            case "INTRADAY_V1" -> "TAKE_PROFIT".equals(signal.signalType)||"STOP_LOSS".equals(signal.signalType)?"公共价位提醒":"短线异动";
            default -> "策略名称未记录";
        };
    }
    public static String phase(SignalEvent signal) {
        return signal.signalAt==null?"收盘信号":"PULLBACK_STRENGTH".equals(signal.signalType)?"盘中转强确认":"盘中完整分钟";
    }
    public static String signalHeader(SignalEvent signal,String action) {
        Kind kind=switch(signal.signalType) {
            case "BUY_WATCH","PULLBACK_STRENGTH" -> Kind.CANDIDATE;
            case "SELL_WATCH","RISK_ALERT","TAKE_PROFIT","STOP_LOSS" -> Kind.RISK;
            default -> Kind.INTRADAY;
        };
        if("RISK_ALERT".equals(signal.signalType)||"STOP_LOSS".equals(signal.signalType))action="🚨 "+action;
        return header(kind,action,strategy(signal),"公共观察",phase(signal));
    }
}
