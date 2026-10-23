package com.stocksentinel.chips;
import org.springframework.web.bind.annotation.*;import org.springframework.beans.factory.ObjectProvider;import org.springframework.http.HttpStatus;import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;import java.util.Map;
@RestController@RequestMapping("/api/v1/chips")
public class ChipController {
    private final ObjectProvider<ChipService> services;
    public ChipController(ObjectProvider<ChipService> services){this.services=services;}
    private ChipService service(){var s=services.getIfAvailable();if(s==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"CHIP_DISABLED");return s;}
    public record Scan(LocalDate asOf,Boolean dryRun){}
    @GetMapping public Object status(@RequestParam(required=false)LocalDate asOf){var s=services.getIfAvailable();return s==null?Map.of("enabled",false,"status","DISABLED"):s.status(asOf);}
    @GetMapping("/items")public Object items(@RequestParam(required=false)LocalDate asOf,@RequestParam(defaultValue="50")int limit,@RequestParam(defaultValue="0")int offset){return service().items(asOf,limit,offset);}
    @PostMapping("/scan")public Object scan(@RequestBody(required=false)Scan r){if(r==null||!Boolean.FALSE.equals(r.dryRun())){var s=services.getIfAvailable();return s==null?Map.of("dryRun",true,"enabled",false):s.scan(r==null?null:r.asOf(),true);}return service().scan(r.asOf(),false);}
    @ExceptionHandler(IllegalArgumentException.class)@ResponseStatus(HttpStatus.BAD_REQUEST)public Object invalid(){return Map.of("error","INVALID_CHIP_REQUEST");}
}
