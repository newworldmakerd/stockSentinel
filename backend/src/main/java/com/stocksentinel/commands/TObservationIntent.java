package com.stocksentinel.commands;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.regex.Pattern;

/** Full literal command, independently checked; a model cannot invent a fill or identity. */
public final class TObservationIntent {
    private TObservationIntent() {}
    public static boolean explicit(String message,String stock,String side,long quantity,BigDecimal price){
        if(message==null||stock==null||price==null)return false;
        String text=Normalizer.normalize(message,Normalizer.Form.NFKC).replaceAll("\\s+","");
        String query=Normalizer.normalize(stock,Normalizer.Form.NFKC).replaceAll("\\s+","");
        String action="BUY".equals(side)?"买入":"SELL".equals(side)?"卖出":"INVALID";
        var match=Pattern.compile("记录"+Pattern.quote(query)+"做[Tt]"+action+"成交([0-9]+)股[,，、]?成交价([0-9]+(?:\\.[0-9]+)?)元[。.!！]?").matcher(text);
        if(!match.matches())return false;
        try{return Long.parseLong(match.group(1))==quantity&&new BigDecimal(match.group(2)).compareTo(price)==0;}catch(NumberFormatException invalid){return false;}
    }
    public static boolean amendment(String message,long id,boolean cancel,Long quantity,BigDecimal price){
        if(message==null)return false;String text=Normalizer.normalize(message,Normalizer.Form.NFKC).replaceAll("\\s+","");
        if(cancel)return text.matches("撤销做[Tt]成交"+id+"[。.!！]?");
        if(quantity==null||price==null)return false;
        var m=Pattern.compile("更正做[Tt]成交"+id+"为([0-9]+)股[,，、]?成交价([0-9]+(?:\\.[0-9]+)?)元[。.!！]?").matcher(text);
        try{return m.matches()&&Long.parseLong(m.group(1))==quantity&&new BigDecimal(m.group(2)).compareTo(price)==0;}catch(NumberFormatException invalid){return false;}
    }
}
