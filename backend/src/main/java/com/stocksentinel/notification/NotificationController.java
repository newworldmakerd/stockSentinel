package com.stocksentinel.notification;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationMapper mapper;
    private final NotificationService service;
    private final NotificationSettings settings;
    public NotificationController(NotificationMapper mapper,NotificationService service,NotificationSettings settings) {
        this.mapper=mapper;this.service=service;this.settings=settings;
    }
    public record TestRequest(Long signalId,Boolean dryRun) {}
    public record Preview(boolean dryRun,String status,Long notificationId) {}
    @GetMapping public Object list(@RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="0") int offset) {
        if(limit<1 || limit>200 || offset<0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_NOTIFICATION_QUERY");
        return mapper.list(limit,offset).stream().map(this::view).toList();
    }
    @GetMapping("/{id}") public Object detail(@PathVariable long id) {
        var row=mapper.find(id);if(row==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"NOTIFICATION_NOT_FOUND");
        return view(row);
    }
    @PostMapping("/test")
    public Preview test(@RequestBody(required=false) TestRequest request) {
        // Safe default: no DB access, enqueue or external connection.
        if(request==null || !Boolean.FALSE.equals(request.dryRun())) return new Preview(true,"DRY_RUN",null);
        if(!settings.enabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"NOTIFICATIONS_DISABLED");
        if(request.signalId()==null || request.signalId()<1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"SIGNAL_ID_REQUIRED");
        var row=service.enqueueManual(request.signalId());
        return new Preview(false,row.status,row.id);
    }
    private Object view(NotificationRow row) {
        // Do not expose destination, payload, owner token or bot credentials.
        var result=new java.util.LinkedHashMap<String,Object>();
        result.put("id",row.id);result.put("signalId",row.signalId);result.put("channel",row.channel);
        result.put("messageType",row.messageType);result.put("status",row.status);result.put("attempts",row.attempts);
        result.put("nextRetryAt",row.nextRetryAt);result.put("lastError",row.lastError);result.put("sentAt",row.sentAt);
        return result;
    }
}
