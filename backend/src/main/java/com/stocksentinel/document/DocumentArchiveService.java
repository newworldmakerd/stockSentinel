package com.stocksentinel.document;

import com.fasterxml.jackson.databind.*;
import com.stocksentinel.jobs.TradingDatePolicy;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** No external write inside a DB transaction; every uncertain write becomes terminal for review. */
public class DocumentArchiveService {
    private final DocumentArchiveMapper mapper;private final DocumentMcpClient client;private final StockDocumentSnapshot snapshots;
    private final TradingDatePolicy dates;private final ObjectMapper json;
    public DocumentArchiveService(DocumentArchiveMapper mapper,DocumentMcpClient client,StockDocumentSnapshot snapshots,TradingDatePolicy dates,ObjectMapper json){this.mapper=mapper;this.client=client;this.snapshots=snapshots;this.dates=dates;this.json=json;}
    public Object status(){var t=mapper.target();var result=new LinkedHashMap<String,Object>();result.put("enabled",true);result.put("status",t.status);result.put("documentUrl",t.docUrl);result.put("docId",t.docId);result.put("sheetId",t.sheetId);result.put("lastError",t.lastError);result.put("authorizationNote","机器人文档授权当前有效期7天，失效需在企微续期");return result;}
    public Object setup(boolean dryRun) {
        if(dryRun)return Map.of("dryRun",true,"action","创建机器人自己的智能表格并初始化文本字段","fields",StockDocumentSnapshot.FIELDS);
        var target=mapper.target();if(!"NEW".equals(target.status))return status();
        // Read-only negotiation may fail without having created anything.
        var session=client.open();if(mapper.beginSetup()!=1)return status();
        try {
            var doc=session.call("create_doc",Map.of("doc_type",10,"doc_name","StockSentinel 每日股票记录"));
            String docId=oneText(doc,"docid"),url=oneText(doc,"url");
            if(!url.startsWith("https://doc.weixin.qq.com/"))throw new DocumentMcpClient.Failure("MCP_DOCUMENT_URL_INVALID",true);
            require(mapper.doc(docId,url));
            var sheets=session.call("smartsheet_get_sheet",Map.of("docid",docId));String sheet=oneText(sheets,"sheet_id");require(mapper.sheet(sheet));
            var fields=session.call("smartsheet_get_fields",Map.of("docid",docId,"sheet_id",sheet));
            var existing=findFieldObjects(fields);if(existing.size()!=1||!"FIELD_TYPE_TEXT".equals(existing.getFirst().path("field_type").asText()))throw new DocumentMcpClient.Failure("MCP_DEFAULT_FIELD_UNEXPECTED",true);
            var first=existing.getFirst();
            if(!StockDocumentSnapshot.FIELDS.getFirst().equals(first.path("field_title").asText()))session.call("smartsheet_update_fields",Map.of("docid",docId,"sheet_id",sheet,"fields",List.of(Map.of("field_id",first.path("field_id").asText(),"field_title",StockDocumentSnapshot.FIELDS.getFirst(),"field_type","FIELD_TYPE_TEXT"))));
            session.call("smartsheet_add_fields",Map.of("docid",docId,"sheet_id",sheet,"fields",StockDocumentSnapshot.FIELDS.subList(1,StockDocumentSnapshot.FIELDS.size()).stream().map(f->Map.of("field_title",f,"field_type","FIELD_TYPE_TEXT")).toList()));
            validateFields(session.call("smartsheet_get_fields",Map.of("docid",docId,"sheet_id",sheet)));
            require(mapper.setupResult("READY",null));
        }catch(Exception failure){mapper.setupResult("UNKNOWN",code(failure));}
        return status();
    }
    public Object preview(LocalDate day){validateDate(day);return Map.of("tradeDate",day,"dryRun",true,"records",snapshots.build(day));}
    private void validateDate(LocalDate date){if(date==null||date.isBefore(LocalDate.of(2000,1,1))||date.isAfter(dates.today()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"DOCUMENT_DATE_INVALID");}
    public Object export(LocalDate day,boolean dryRun) {
        if(dryRun)return preview(day);validateDate(day);
        if(!"OPEN".equals(dates.calendarStatus(day))||day.equals(dates.today())&&!dates.afterClose()||!mapper.reportExists(day))throw new ResponseStatusException(HttpStatus.CONFLICT,"FROZEN_CLOSED_DAY_REPORT_REQUIRED");
        var target=mapper.target();if(!"READY".equals(target.status))throw new ResponseStatusException(HttpStatus.CONFLICT,"DOCUMENT_SETUP_REQUIRED");
        var existing=mapper.export(day);if(existing==null){var records=snapshots.build(day);try{mapper.freeze(day,target.docId,target.sheetId,json.writeValueAsString(records),records.size());}catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new IllegalStateException("DOCUMENT_SNAPSHOT_INVALID");}}
        if(mapper.claim(day)!=1)return exportStatus(day);
        boolean mutationStarted=false;
        try {
            var frozen=mapper.export(day);var rows=json.readTree(frozen.records);
            if(rows.isEmpty()){mapper.finish(day,"EMPTY",null);return exportStatus(day);}
            var session=client.open();validateFields(session.call("smartsheet_get_fields",Map.of("docid",frozen.docId,"sheet_id",frozen.sheetId)));
            for(int offset=frozen.confirmedRows;offset<rows.size();offset+=100){var batch=new ArrayList<JsonNode>();for(int i=offset;i<Math.min(offset+100,rows.size());i++)batch.add(rows.get(i));
                mutationStarted=true;
                var response=session.call("smartsheet_add_records",Map.of("docid",frozen.docId,"sheet_id",frozen.sheetId,"records",batch));
                var ids=new LinkedHashSet<String>();collectText(response,"record_id",ids);
                if(ids.size()!=batch.size())throw new DocumentMcpClient.Failure("MCP_RECORD_ACK_INCOMPLETE",true);
                require(mapper.progress(day,offset+batch.size()));mutationStarted=false;
            }
            require(mapper.finish(day,"SUCCESS",null));
        }catch(Exception failure){mapper.finish(day,mutationStarted?"UNKNOWN":"FAILED",code(failure));}
        return exportStatus(day);
    }
    public Object retry(LocalDate day,boolean dryRun){
        validateDate(day);var row=mapper.export(day);
        if(dryRun)return Map.of("dryRun",true,"canRetry",row!=null&&"FAILED".equals(row.status),"status",row==null?"NOT_CREATED":row.status);
        if(row==null||!"FAILED".equals(row.status))throw new ResponseStatusException(HttpStatus.CONFLICT,"ONLY_CONFIRMED_FAILED_EXPORT_CAN_RETRY");
        // Preserve the frozen snapshot and confirmed prefix; UNKNOWN/SENDING/SUCCESS never enter this path.
        if(mapper.retryConfirmedFailure(day)!=1)return exportStatus(day);
        return export(day,false);
    }
    public Object exportStatus(LocalDate day){validateDate(day);var row=mapper.export(day);if(row==null)return Map.of("tradeDate",day,"status","NOT_CREATED");var out=new LinkedHashMap<String,Object>();out.put("tradeDate",day);out.put("status",row.status);out.put("totalRows",row.totalRows);out.put("confirmedRows",row.confirmedRows);out.put("lastError",row.lastError);return out;}
    static void validateFields(JsonNode result){var fields=findFieldObjects(result);var names=new HashSet<String>();for(var f:fields){if(!names.add(f.path("field_title").asText())||!"FIELD_TYPE_TEXT".equals(f.path("field_type").asText()))throw new DocumentMcpClient.Failure("MCP_FIELD_SCHEMA_CHANGED",false);}if(!names.containsAll(StockDocumentSnapshot.FIELDS))throw new DocumentMcpClient.Failure("MCP_FIELD_SCHEMA_CHANGED",false);}
    static List<JsonNode> findFieldObjects(JsonNode node){var result=new ArrayList<JsonNode>();if(node.isObject()&&node.has("field_id")&&node.has("field_title")&&node.has("field_type"))result.add(node);else if(node.isContainerNode())node.forEach(child->result.addAll(findFieldObjects(child)));return result;}
    static String oneText(JsonNode node,String field){var values=new LinkedHashSet<String>();collectText(node,field,values);if(values.size()!=1)throw new DocumentMcpClient.Failure("MCP_TARGET_RESPONSE_INVALID",true);return values.iterator().next();}
    private static void collectText(JsonNode node,String key,Set<String> result){if(node.isObject()&&node.path(key).isTextual()&&!node.path(key).asText().isBlank())result.add(node.path(key).asText());if(node.isContainerNode())node.forEach(child->collectText(child,key,result));}
    private static void require(int count){if(count!=1)throw new IllegalStateException("DOCUMENT_STATE_CHANGED");}
    private static String code(Exception failure){return failure instanceof DocumentMcpClient.Failure f?f.code:"DOCUMENT_OPERATION_FAILED";}
}
