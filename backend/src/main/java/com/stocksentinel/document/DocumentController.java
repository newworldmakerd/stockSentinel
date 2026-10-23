package com.stocksentinel.document;

import java.time.LocalDate;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/documents/wecom")
public class DocumentController {
    private final ObjectProvider<DocumentArchiveService> services;private final DocumentSettings settings;
    public DocumentController(ObjectProvider<DocumentArchiveService> services,DocumentSettings settings){this.services=services;this.settings=settings;}
    private DocumentArchiveService service(){var service=services.getIfAvailable();if(!settings.enabled||service==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"WECOM_DOCUMENT_DISABLED");return service;}
    public record Setup(Boolean dryRun) {}
    public record Export(LocalDate tradeDate,Boolean dryRun) {}
    @GetMapping public Object status(){return settings.enabled?service().status():Map.of("enabled",false,"status","DISABLED");}
    @PostMapping("/setup") public Object setup(@RequestBody(required=false) Setup request){if(request==null||!Boolean.FALSE.equals(request.dryRun()))return Map.of("dryRun",true,"fields",StockDocumentSnapshot.FIELDS);return service().setup(false);}
    @GetMapping("/preview") public Object preview(@RequestParam LocalDate tradeDate){return service().preview(tradeDate);}
    @PostMapping("/exports") public Object export(@RequestBody Export request){return service().export(request.tradeDate(),!Boolean.FALSE.equals(request.dryRun()));}
    @GetMapping("/exports/{day}") public Object exportStatus(@PathVariable LocalDate day){return service().exportStatus(day);}
    @PostMapping("/exports/{day}/retry") public Object retry(@PathVariable LocalDate day,@RequestBody(required=false) Setup request){return service().retry(day,request==null||!Boolean.FALSE.equals(request.dryRun()));}
    @ExceptionHandler(DocumentMcpClient.Failure.class) @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public Object failure(DocumentMcpClient.Failure failure){return Map.of("error",failure.code,"resultUncertain",failure.uncertain);}
}
