package com.stocksentinel.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksentinel.advisory.Advice;
import com.stocksentinel.common.entity.SignalEvent;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

@Component
public class NotificationTemplate {
    private final ObjectMapper json;
    public NotificationTemplate(ObjectMapper json) { this.json=json; }
    public String render(SignalEvent s,String advice) {
        return render(s,advice,null);
    }
    public String render(SignalEvent s,String advice,String stockName) {
        if(s==null || s.symbol==null || !s.symbol.matches("[0-9]{6}") || s.tradeDate==null
                || s.referencePrice==null || s.referencePrice.signum()<=0)
            throw new IllegalArgumentException("Invalid notification signal");
        String title=switch(s.signalType) {
            case "BUY_WATCH" -> "买入观察"; case "SELL_WATCH" -> "卖出观察";
            case "RISK_ALERT" -> "风险预警";
            case "PULLBACK_STRENGTH" -> "强势股缩量回踩·转强观察";
            case "RAPID_RISE" -> "盘中快速拉升";case "RAPID_DROP" -> "盘中快速跳水";
            case "BREAKOUT_WATCH" -> "盘中突破观察";case "TAKE_PROFIT" -> "止盈价位提醒";case "STOP_LOSS" -> "止损价位提醒";
            default -> throw new IllegalArgumentException("Unsupported signal type");
        };
        if("INTRADAY_MA60_V1".equals(s.strategyCode)&&"RISK_ALERT".equals(s.signalType))title="盘中跌破MA60·风险预警";
        StringBuilder b=new StringBuilder(MessageIdentity.signalHeader(s,title));
        b.append("股票：").append(stockName==null||stockName.isBlank()?"":safe(stockName,30)+" ").append(safe(s.symbol,6))
            .append("\n🕒 ").append(s.signalAt==null?s.tradeDate+" 收盘":MessageDisplay.time(s.signalAt)).append("（北京时间）")
            .append("\n💰 参考价格：").append(s.referencePrice.stripTrailingZeros().toPlainString())
            .append(s.signalAt==null ? " 元｜不复权收盘价" : " 元｜不复权分钟收盘价");
        String adviceRisk="";
        if (advice!=null) {
            try {
                Advice a=json.readValue(advice,Advice.class);
                b.append("\n\n🤖 大模型辅助建议（摘要）\n").append(safe(a.suggestion(),180));
                adviceRisk="\n\n⚠️ AI风险："+safe(String.join("；",a.risks().stream().limit(2).toList()),180)
                    +(a.risks().size()>2?"（其余风险未展开）":"")
                    +"\n失效条件："+safe(a.invalidation(),100);
            } catch (Exception invalid) { b.append("\n\n大模型建议：内容不可用；保留上述规则信号。"); }
        } else b.append("\n\n大模型建议：暂未生成或未启用。");
        b.append("\n\n📍 触发原因\n").append(safe(displayNumbers(s.reason),320)).append(adviceRisk)
            .append("\n\n⚠️ ").append(safe(s.riskNote,220))
            .append("\n来源：本地确定性规则").append(advice==null?"":"；来源：大模型辅助建议")
            .append("\n仅供辅助分析，不构成收益承诺；不自动下单。");
        // Keep a conservative 4000-byte message, preserving UTF-8 codepoint boundaries.
        String result=b.toString();
        if (result.getBytes(StandardCharsets.UTF_8).length>4000) {
            String suffix="\n（内容截断）\n仅供辅助分析，不构成收益承诺；不自动下单。";
            int budget=4000-suffix.getBytes(StandardCharsets.UTF_8).length;
            StringBuilder cut=new StringBuilder(); int bytes=0;
            for (int cp:result.codePoints().toArray()) {
                String part=new String(Character.toChars(cp)); int size=part.getBytes(StandardCharsets.UTF_8).length;
                if (bytes+size>budget) break; cut.append(part); bytes+=size;
            }
            result=cut+suffix;
        }
        return result;
    }
    private static String displayNumbers(String text) {
        if(text==null)return null;
        return java.util.regex.Pattern.compile("(?<![\\w.])-?\\d+\\.\\d{5,}(?![\\w.])").matcher(text).replaceAll(match->{
            var number=new java.math.BigDecimal(match.group());
            var rounded=number.setScale(4,java.math.RoundingMode.HALF_UP);
            return rounded.signum()==0&&number.signum()!=0?match.group():rounded.stripTrailingZeros().toPlainString();
        });
    }
    private String safe(String value,int max) {
        if (value==null) return "未提供";
        String clean=value.replaceAll("[\\p{Cntrl}<>`*_\\[\\]()]"," ");
        return clean.codePointCount(0,clean.length())<=max ? clean : clean.substring(0,clean.offsetByCodePoints(0,max))+"…";
    }
}
