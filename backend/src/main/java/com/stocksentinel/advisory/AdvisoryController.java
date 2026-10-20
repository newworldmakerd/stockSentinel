package com.stocksentinel.advisory;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/signals")
public class AdvisoryController {
    private final SignalAdvisoryService service;
    public AdvisoryController(SignalAdvisoryService service) { this.service = service; }
    @PostMapping("/{id}/advice")
    public ResponseEntity<?> analyze(@PathVariable long id) {
        var result = service.analyze(id);
        int code = switch (result) {
            case DISABLED -> 503;
            case NOT_FOUND -> 404;
            case FAILED -> 502;
            default -> 200;
        };
        return ResponseEntity.status(code).body(java.util.Map.of("status", result));
    }
}