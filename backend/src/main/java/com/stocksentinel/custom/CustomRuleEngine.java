package com.stocksentinel.custom;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;import java.util.*;

/** Whitelisted data only: no expression language, scripts, reflection or SQL fragments. */
public final class CustomRuleEngine {
    private CustomRuleEngine(){}
    public enum Factor {
        CLOSE("收盘价","PRICE"),OPEN("开盘价","PRICE"),HIGH("最高价","PRICE"),LOW("最低价","PRICE"),
        MA5("MA5","PRICE"),MA10("MA10","PRICE"),MA20("MA20","PRICE"),MA60("MA60","PRICE"),
        DIF("MACD DIF","PRICE"),DEA("MACD DEA","PRICE"),MACD_HIST("MACD柱","PRICE"),
        VOLUME_RATIO("量比（前5日均量）","RATIO"),VOLUME("成交量（股）","SHARE"),AMOUNT("成交额（元）","CNY"),
        CHIP90("90%筹码集中度","RATIO"),CHIP70("70%筹码集中度","RATIO"),CHIP_PROFIT("筹码获利比例","RATIO");
        public final String label,unit;Factor(String label,String unit){this.label=label;this.unit=unit;}
    }
    public enum Operator {GT,GE,LT,LE,EQ,CROSS_UP,CROSS_DOWN,RISING,FALLING}
    public record Value(BigDecimal current,BigDecimal previous){}
    public record Result(String status,List<Map<String,Object>> conditions){}
    public static Object catalog(){return Arrays.stream(Factor.values()).map(f->Map.of("code",f.name(),"label",f.label,"unit",f.unit,"requirement",f.name().startsWith("CHIP")?"需同日筹码估算落库，首期该任务尚未启用":f.name().startsWith("MA")||Set.of(Factor.DIF,Factor.DEA,Factor.MACD_HIST,Factor.VOLUME_RATIO).contains(f)?"需同源、同复权且未过期的日线指标":"已收盘日线")).toList();}
    static void fields(JsonNode node,Set<String> allowed){if(node==null||!node.isObject())throw new IllegalArgumentException("INVALID_RULE_OBJECT");node.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw new IllegalArgumentException("UNKNOWN_RULE_FIELD");});}
    public static void validate(JsonNode definition){
        fields(definition,Set.of("schemaVersion","adjustment","universe","symbols","rules"));
        if(!"1".equals(definition.path("schemaVersion").asText())||!Set.of("QFQ","NONE").contains(definition.path("adjustment").asText())||!Set.of("WATCHLIST","POSITIONS","SYMBOLS").contains(definition.path("universe").asText()))throw new IllegalArgumentException("INVALID_DEFINITION");
        if("SYMBOLS".equals(definition.path("universe").asText()))symbols(definition.path("symbols"),100);
        else if(definition.has("symbols")&&(!definition.path("symbols").isArray()||!definition.path("symbols").isEmpty()))throw new IllegalArgumentException("UNEXPECTED_SYMBOLS");
        if(!definition.path("rules").has("logic"))throw new IllegalArgumentException("ROOT_GROUP_REQUIRED");
        int[] leaves={0};validateNode(definition.path("rules"),0,leaves);if(leaves[0]==0||leaves[0]>20)throw new IllegalArgumentException("RULE_COUNT_LIMIT");
    }
    public static List<String> symbols(JsonNode node,int maximum){if(node==null||!node.isArray()||node.isEmpty()||node.size()>maximum)throw new IllegalArgumentException("INVALID_SYMBOL_SCOPE");var out=new ArrayList<String>();for(var value:node){if(!value.isTextual()||!value.asText().matches("[0-9]{6}")||out.contains(value.asText()))throw new IllegalArgumentException("INVALID_SYMBOL_SCOPE");out.add(value.asText());}return out;}
    private static void validateNode(JsonNode n,int depth,int[] leaves){
        if(n.has("logic")){fields(n,Set.of("logic","children"));if(depth>1||!Set.of("AND","OR").contains(n.path("logic").asText())||!n.path("children").isArray()||n.path("children").isEmpty()||n.path("children").size()>20)throw new IllegalArgumentException("INVALID_RULE_GROUP");for(var child:n.path("children"))validateNode(child,depth+1,leaves);}
        else{fields(n,Set.of("factor","operator","operand"));leaves[0]++;var factor=Factor.valueOf(n.path("factor").asText());var op=Operator.valueOf(n.path("operator").asText());
            if(op==Operator.RISING||op==Operator.FALLING){if(n.has("operand")&&!n.path("operand").isNull())throw new IllegalArgumentException("UNEXPECTED_OPERAND");return;}
            var operand=n.path("operand");fields(operand,Set.of("factor","value"));if(operand.has("factor")==operand.has("value"))throw new IllegalArgumentException("INVALID_OPERAND");
            if(operand.has("factor")){if(!factor.unit.equals(Factor.valueOf(operand.path("factor").asText()).unit))throw new IllegalArgumentException("INCOMPATIBLE_FACTOR_UNITS");}
            else{if(!operand.path("value").isNumber())throw new IllegalArgumentException("NUMERIC_OPERAND_REQUIRED");var number=operand.path("value").decimalValue();if(number.precision()>15||Math.abs(number.scale())>8)throw new IllegalArgumentException("NUMERIC_OPERAND_LIMIT");}
        }
    }
    public static Result evaluate(JsonNode definition,Map<Factor,Value> values){validate(definition);var conditions=new ArrayList<Map<String,Object>>();boolean[] missing={false};boolean matched=evaluateNode(definition.path("rules"),values,conditions,missing);return new Result(missing[0]?"SKIPPED":matched?"MATCHED":"NO_MATCH",conditions);}
    private static boolean evaluateNode(JsonNode n,Map<Factor,Value> values,List<Map<String,Object>> rows,boolean[] missing){
        if(n.has("logic")){boolean all=true,any=false;for(var child:n.path("children")){boolean ok=evaluateNode(child,values,rows,missing);all&=ok;any|=ok;}return n.path("logic").asText().equals("AND")?all:any;}
        var factor=Factor.valueOf(n.path("factor").asText());var op=Operator.valueOf(n.path("operator").asText());var left=values.getOrDefault(factor,new Value(null,null));var operand=n.path("operand");
        var right=op==Operator.RISING||op==Operator.FALLING?left:operand.has("factor")?values.getOrDefault(Factor.valueOf(operand.path("factor").asText()),new Value(null,null)):new Value(operand.path("value").decimalValue(),operand.path("value").decimalValue());
        boolean needsPrevious=Set.of(Operator.CROSS_UP,Operator.CROSS_DOWN,Operator.RISING,Operator.FALLING).contains(op);
        boolean absent=left.current()==null||right.current()==null||needsPrevious&&(left.previous()==null||right.previous()==null);
        boolean ok=false;if(!absent){int c=left.current().compareTo(right.current());ok=switch(op){case GT->c>0;case GE->c>=0;case LT->c<0;case LE->c<=0;case EQ->c==0;case CROSS_UP->left.previous().compareTo(right.previous())<=0&&c>0;case CROSS_DOWN->left.previous().compareTo(right.previous())>=0&&c<0;case RISING->left.current().compareTo(left.previous())>0;case FALLING->left.current().compareTo(left.previous())<0;};}
        boolean direction=op==Operator.RISING||op==Operator.FALLING;
        var row=new LinkedHashMap<String,Object>();row.put("factor",factor.name());row.put("label",factor.label);row.put("operator",op.name());row.put("operand",operand.isMissingNode()?null:operand);row.put("current",left.current());row.put("previous",left.previous());row.put("threshold",direction?left.previous():right.current());row.put("previousThreshold",direction?null:right.previous());row.put("status",absent?"MISSING":ok?"PASS":"FAIL");rows.add(row);missing[0]|=absent;return ok;
    }
}
