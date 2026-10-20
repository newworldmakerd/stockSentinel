package com.stocksentinel.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/strategies")
public class StrategyController {
    public record Update(String version,Boolean enabled,StrategyParameters parameters) {}
    public record Evaluation(String symbol,LocalDate tradeDate) {}
    private final StrategyMapper mapper;
    private final StrategyDefinitionService definitions;
    private final StrategyEvaluationService evaluations;
    private final ObjectMapper json;
    public StrategyController(StrategyMapper mapper,StrategyDefinitionService definitions,StrategyEvaluationService evaluations,ObjectMapper json) {
        this.mapper=mapper; this.definitions=definitions; this.evaluations=evaluations; this.json=json;
    }
    @GetMapping public Object list() { return mapper.list().stream().map(this::view).toList(); }
    @PutMapping("/{code}") public Object update(@PathVariable String code,@RequestBody String body) {
        Update request;
        try { request=json.readerFor(Update.class).with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(body); }
        catch (Exception invalid) { throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"INVALID_STRATEGY_UPDATE"); }
        return view(definitions.update(code,request.version(),request.enabled(),json.valueToTree(request.parameters())));
    }
    @GetMapping("/{code}/revisions") public Object revisions(@PathVariable String code) {
        definitions.current(code);
        return mapper.revisions(code).stream().map(value->{try{return json.readTree(value);}catch(Exception failure){throw new IllegalStateException("Invalid stored revision",failure);}}).toList();
    }
    @PostMapping("/{code}/evaluate") public Object evaluate(@PathVariable String code,@RequestBody Evaluation request) {
        return evaluations.evaluate(code,request.symbol(),request.tradeDate());
    }
    private JsonNode view(com.stocksentinel.common.entity.StrategyDefinition definition) {
        var node=json.valueToTree(definition);
        try { ((com.fasterxml.jackson.databind.node.ObjectNode)node).set("parameters",json.readTree(definition.parameters)); }
        catch (Exception failed) { throw new IllegalStateException("Invalid stored strategy",failed); }
        return node;
    }
}