package com.stocksentinel.notification;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Component
public class NotificationWorker {
    private final NotificationService service;
    private final ObjectProvider<NotificationClient> clients;
    private final NotificationSettings settings;
    private final boolean workerEnabled;
    public NotificationWorker(NotificationService service,ObjectProvider<NotificationClient> clients,NotificationSettings settings,
            @Value("${wecom.worker-enabled:true}") boolean workerEnabled) {
        this.service=service;this.clients=clients;this.settings=settings;this.workerEnabled=workerEnabled;
    }
    @Scheduled(fixedDelayString="${wecom.poll-ms:5000}",initialDelayString="${wecom.poll-ms:5000}")
    public void tick() { if (workerEnabled) dispatchOne(); }
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public boolean dispatchOne() {
        if (!settings.enabled) return false;
        var client=clients.getIfAvailable();
        if (client==null) return false;
        var row=service.claim(); if(row==null) return false;
        NotificationClient.Delivery result;
        try { result=client.send(row.targetChatId,row.payload); }
        catch (Exception unconfirmed) { result=new NotificationClient.Delivery(NotificationClient.Outcome.UNKNOWN,"CLIENT_FAILED"); }
        service.finish(row,result);
        return true;
    }
}
