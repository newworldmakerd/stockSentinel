package com.stocksentinel.report;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reports/daily/resends")
public class ReportResendController {
    public record Request(String requestId) {}
    private final ReportResendService service;
    public ReportResendController(ReportResendService service){this.service=service;}
    @PostMapping public Object resend(@RequestBody Request request){return service.enqueue(request.requestId());}
    @GetMapping("/{requestId}") public Object status(@PathVariable String requestId){return service.status(requestId);}
}
