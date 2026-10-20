package com.stocksentinel.signal;

import com.stocksentinel.advisory.SignalAdvisoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

@Component
public class StrategyAdvisoryListener {
    private static final Logger LOG=LoggerFactory.getLogger(StrategyAdvisoryListener.class);
    private final SignalAdvisoryService advisory;
    public StrategyAdvisoryListener(SignalAdvisoryService advisory) { this.advisory=advisory; }
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void created(SignalCreated event) {
        try { advisory.analyze(event.signalId()); }
        catch (Exception failure) { LOG.warn("Advisory failed after committed signal {}: {}",event.signalId(),failure.getClass().getSimpleName()); }
    }
}