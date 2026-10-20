package com.stocksentinel.notification;

import com.stocksentinel.signal.SignalCreated;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

@Component
public class NotificationListener {
    private final NotificationService service;
    public NotificationListener(NotificationService service) { this.service=service; }
    @TransactionalEventListener(phase=TransactionPhase.BEFORE_COMMIT)
    public void created(SignalCreated event) { service.enqueue(event.signalId()); }
}
