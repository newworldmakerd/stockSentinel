package com.stocksentinel.custom;
import com.fasterxml.jackson.databind.*;import org.springframework.web.bind.annotation.*;import org.springframework.beans.factory.ObjectProvider;import org.springframework.http.HttpStatus;import org.springframework.web.server.ResponseStatusException;import java.util.*;
@RestController@RequestMapping("/api/v1/custom-strategies")
public class CustomStrategyController {
    private final ObjectProvider<CustomStrategyService> services;private final ObjectMapper json;
    public CustomStrategyController(ObjectProvider<CustomStrategyService> services,ObjectMapper json){this.services=services;this.json=json;}
    private CustomStrategyService service(){var s=services.getIfAvailable();if(s==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"CUSTOM_MANAGEMENT_DISABLED");return s;}
    private <T>T read(String body,Class<T> type){try{if(body.length()>65536)throw new IllegalArgumentException();return json.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(body);}catch(Exception e){throw new IllegalArgumentException("INVALID_CUSTOM_REQUEST");}}
    @GetMapping("/catalog")public Object catalog(){return Map.of("factors",CustomRuleEngine.catalog(),"operators",CustomRuleEngine.Operator.values(),"maxConditions",20,"maxGroupDepth",2,"maxSymbols",100);}
    @GetMapping public Object list(){var s=services.getIfAvailable();return s==null?Map.of("enabled",false,"notificationsEnabled",false,"automaticScan",false,"strategies",List.of()):s.list();}
    @GetMapping("/{id}")public Object detail(@PathVariable UUID id){return service().detail(id);}
    @PostMapping(consumes="application/json")public Object create(@RequestBody String body){return service().save(null,read(body,CustomStrategyService.Save.class));}
    @PutMapping(value="/{id}",consumes="application/json")public Object save(@PathVariable UUID id,@RequestBody String body){return service().save(id,read(body,CustomStrategyService.Save.class));}
    @PostMapping(value="/{id}/state",consumes="application/json")public Object state(@PathVariable UUID id,@RequestBody String body){return service().state(id,read(body,CustomStrategyService.State.class));}
    @PostMapping(value="/{id}/preview",consumes="application/json")public Object preview(@PathVariable UUID id,@RequestBody String body){return service().execute(id,read(body,CustomStrategyService.Execute.class),true);}
    @PostMapping(value="/{id}/scan",consumes="application/json")public Object scan(@PathVariable UUID id,@RequestBody String body){return service().execute(id,read(body,CustomStrategyService.Execute.class),false);}
    @ExceptionHandler(IllegalArgumentException.class)@ResponseStatus(HttpStatus.BAD_REQUEST)public Object invalid(){return Map.of("error","INVALID_CUSTOM_REQUEST");}
    @ExceptionHandler(ResponseStatusException.class)public org.springframework.http.ResponseEntity<?> problem(ResponseStatusException e){return org.springframework.http.ResponseEntity.status(e.getStatusCode()).body(Map.of("error",e.getReason()==null?"CUSTOM_REQUEST_FAILED":e.getReason()));}
}
