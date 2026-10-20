package com.stocksentinel.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksentinel.common.entity.StrategyDefinition;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StrategyDefinitionService {
    public static final String CODE="SWING_TREND_V1";
    private final StrategyMapper mapper;
    private final ObjectMapper json;
    public StrategyDefinitionService(StrategyMapper mapper,ObjectMapper json) { this.mapper=mapper; this.json=json.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS); }
    public StrategyParameters parameters(String raw) {
        try {
            JsonNode node=json.readTree(raw);
            var allowed=Set.of("volumeMultiplier","adjustment","calculationVersion","riskMode","sellMode");
            if (!node.isObject() || node.size()!=allowed.size()) throw new IllegalArgumentException();
            var names=node.fieldNames();
            while(names.hasNext()) if (!allowed.contains(names.next())) throw new IllegalArgumentException();
            return json.treeToValue(node,StrategyParameters.class);
        } catch (Exception invalid) { throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_STRATEGY_PARAMETERS"); }
    }
    public StrategyDefinition current(String code) {
        if (!CODE.equals(code)) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"UNSUPPORTED_STRATEGY");
        var definition=mapper.find(code);
        if (definition==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"STRATEGY_NOT_FOUND");
        parameters(definition.parameters);
        return definition;
    }
    @Transactional public StrategyDefinition update(String code,String version,Boolean enabled,JsonNode parameters) {
        if (!CODE.equals(code) || version==null || !version.matches("[A-Za-z0-9_.-]{1,30}") || enabled==null || parameters==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_STRATEGY_UPDATE");
        String payload=parameters.toString(); parameters(payload);
        var existing=mapper.locked(code);
        if (existing==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"STRATEGY_NOT_FOUND");
        if (existing.version.equals(version) && !same(existing.parameters,payload))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"PARAMETER_CHANGE_REQUIRES_NEW_VERSION");
        mapper.addRevision(code,existing.version,existing.parameters);
        String revision=mapper.revision(code,version);
        if (revision!=null && !same(revision,payload)) throw new ResponseStatusException(HttpStatus.CONFLICT,"VERSION_ALREADY_HAS_DIFFERENT_PARAMETERS");
        mapper.addRevision(code,version,payload);
        mapper.update(code,version,payload,enabled);
        return mapper.find(code);
    }
    public boolean same(String left,String right) {
        try { return json.readTree(left).equals(json.readTree(right)); }
        catch (Exception invalid) { return false; }
    }
}