package com.stocksentinel.commands;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/intraday/subscriptions")
public class MonitorSubscriptionController {
    public record Request(String symbol) {}
    private final MonitorSubscriptionService service;
    public MonitorSubscriptionController(MonitorSubscriptionService service) {this.service=service;}
    @PostMapping public MonitorSubscriptionService.Result add(@RequestBody Request request) {return service.add(request.symbol());}
}
