package com.stocksentinel.advisory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SignalAdvisoryService {
    public static final String PROMPT_VERSION = "signal-advice-v1";
    public enum Status { DISABLED, NOT_FOUND, ALREADY_ANALYZED, SUCCESS, FAILED }
    private final AdvisoryClient client;
    private final AdviceStore store;
    private final ObjectMapper mapper;
    private final String model;
    public SignalAdvisoryService(ObjectProvider<AdvisoryClient> client, AdviceStore store,
            ObjectMapper mapper, @Value("${llm.model:}") String model) {
        this.client = client.getIfAvailable();
        this.store = store;
        this.mapper = mapper;
        this.model = model;
    }
    /** Call only after the rule signal transaction commits. No external call while disabled. */
    public synchronized Status analyze(long signalId) {
        if (client == null) return Status.DISABLED;
        if (store.existing(signalId, model, PROMPT_VERSION) > 0) return Status.ALREADY_ANALYZED;
        String input = store.loadVerifiedSignal(signalId);
        if (input == null) return Status.NOT_FOUND;
        String output;
        try {
            if (input.length() > 60000) throw new IllegalArgumentException("Signal snapshot too large");
            output = mapper.writeValueAsString(java.util.Objects.requireNonNull(client.analyze(input)));
        } catch (Exception failure) {
            store.save(signalId, model, PROMPT_VERSION, "FAILED", input, null, "MODEL_ANALYSIS_FAILED");
            return Status.FAILED;
        }
        store.save(signalId, model, PROMPT_VERSION, "SUCCESS", input, output, null);
        return Status.SUCCESS;
    }
}