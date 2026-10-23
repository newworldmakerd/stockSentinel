package com.stocksentinel.pullback;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/pullback")
public class PullbackController {
    private final PullbackService service;
    private final boolean digestEnabled;
    public PullbackController(PullbackService service,@org.springframework.beans.factory.annotation.Value("${pullback.digest-enabled:false}") boolean digestEnabled){this.service=service;this.digestEnabled=digestEnabled;}
    @GetMapping public Map<String,Object> status(){return Map.of("parameters",service.settings(),"version",service.settings().version(),"pool",service.pool(),"recommendations",Map.of("enabled",digestEnabled&&service.settings().enabled(),"frequencyPerTradingDay",2,"times",java.util.List.of("09:40","15:30"),"maxCandidates",5,"timezone","Asia/Shanghai","slots","交易日09:40、15:30各一次"));}
}
