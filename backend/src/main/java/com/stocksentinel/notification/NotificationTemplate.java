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
        if(s==null || s.symbol==null || !s.symbol.matches("[0-9]{6}") || s.tradeDate==null
                || s.referencePrice==null || s.referencePrice.signum()<=0)
            throw new IllegalArgumentException("Invalid notification signal");
        String title=switch(s.signalType) {
            case "BUY_WATCH" -> "买入观察"; case "SELL_WATCH" -> "卖出观察";
            case "RISK_ALERT" -> "风险预警";
            case "RAPID_RISE" -> "盘中快速拉升";case "RAPID_DROP" -> "盘中快速跳水";
            case "BREAKOUT_WATCH" -> "盘中突破观察";case "TAKE_PROFIT" -> "止盈价位提醒";case "STOP_LOSS" -> "止损价位提醒";
            default -> throw new IllegalArgumentException("Unsupported signal type");
        };
        StringBuilder b=new StringBuilder("【StockSentinel · "+title+"】\n");
        b.append("来源：本地确定性规则\n股票：").append(safe(s.symbol,6)).append("\n交易日：").append(s.tradeDate)
            .append("\n参考价格：").append(s.referencePrice).append(s.signalAt==null ? " 元（不复权收盘价）\n策略：" : " 元（不复权分钟收盘价）\n策略：")
            .append(safe(s.strategyCode,80)).append(" / ").append(safe(s.strategyVersion,40))
            .append("\n规则依据：").append(safe(s.reason,1000)).append("\n规则风险：").append(safe(s.riskNote,500));
        if(s.signalAt!=null) b.append("\n分钟时间：").append(s.signalAt.atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai")));
        if (advice!=null) {
            try {
                Advice a=json.readValue(advice,Advice.class);
                b.append("\n\n来源：大模型辅助建议（不改变规则信号）\n建议：").append(safe(a.suggestion(),400))
                    .append("\n依据：").append(safe(String.join("；",a.reasons()),400))
                    .append("\n风险：").append(safe(String.join("；",a.risks()),400))
                    .append("\n失效条件：").append(safe(a.invalidation(),300));
            } catch (Exception invalid) { b.append("\n\n大模型建议：内容不可用；保留上述规则信号。"); }
        } else b.append("\n\n大模型建议：暂未生成或未启用。");
        b.append("\n\n仅供辅助分析，不构成收益承诺；不自动下单。");
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
    private String safe(String value,int max) {
        if (value==null) return "未提供";
        String clean=value.replaceAll("[\\p{Cntrl}<>`*_\\[\\]()]"," ");
        return clean.codePointCount(0,clean.length())<=max ? clean : clean.substring(0,clean.offsetByCodePoints(0,max))+"…";
    }
}
