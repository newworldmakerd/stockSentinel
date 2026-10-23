package com.stocksentinel.commands;
import java.math.BigDecimal;import java.text.Normalizer;import java.util.regex.*;
public final class TrendIntent {
    private TrendIntent(){}
    private static String clean(String s){return s==null?"":Normalizer.normalize(s,Normalizer.Form.NFKC).replaceAll("\\s+","");}
    private static boolean values(String text,String expression,BigDecimal a,BigDecimal b){var m=Pattern.compile(expression).matcher(clean(text));try{return m.matches()&&a!=null&&b!=null&&new BigDecimal(m.group(1)).compareTo(a)==0&&new BigDecimal(m.group(2)).compareTo(b)==0;}catch(Exception bad){return false;}}
    public static boolean buy(String text,String stock,long qty,BigDecimal price){return values(text,"记录"+Pattern.quote(clean(stock))+"趋势买入([0-9]+)股[,，、]?成交价([0-9]+(?:\\.[0-9]+)?)元[。.!！]?",BigDecimal.valueOf(qty),price);}
    public static boolean sell(String text,long lot,long qty,BigDecimal price){return values(text,"记录趋势批次"+lot+"卖出([0-9]+)股[,，、]?成交价([0-9]+(?:\\.[0-9]+)?)元[。.!！]?",BigDecimal.valueOf(qty),price);}
    public static boolean account(String text,BigDecimal equity,BigDecimal value){return values(text,"设置趋势账户资产([0-9]+(?:\\.[0-9]+)?)元[,，、]?股票市值([0-9]+(?:\\.[0-9]+)?)元[。.!！]?",equity,value);}
    public static boolean plan(String text,BigDecimal price,BigDecimal value){String clean=clean(text);var p=Pattern.compile("计划价([0-9]+(?:\\.[0-9]+)?)元").matcher(clean);if(!p.find()||new BigDecimal(p.group(1)).compareTo(price)!=0)return false;if(value==null)return true;var v=Pattern.compile("已有该股市值([0-9]+(?:\\.[0-9]+)?)元").matcher(clean);return v.find()&&new BigDecimal(v.group(1)).compareTo(value)==0;}
}
