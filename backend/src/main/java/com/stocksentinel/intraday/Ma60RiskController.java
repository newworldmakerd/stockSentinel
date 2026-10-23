package com.stocksentinel.intraday;
import com.stocksentinel.jobs.TradingDatePolicy;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
@RestController
public class Ma60RiskController {
    private final Ma60RiskMapper mapper;private final Ma60RiskSettings settings;private final TradingDatePolicy dates;
    public Ma60RiskController(Ma60RiskMapper mapper,Ma60RiskSettings settings,TradingDatePolicy dates){this.mapper=mapper;this.settings=settings;this.dates=dates;}
    @GetMapping("/api/v1/intraday/ma60-risk") public Object status(){return Map.of("enabled",settings.enabled(),"intervalMinutes",1,
        "cooldownMinutes",settings.cooldownMinutes(),"scope","ENABLED_INTRADAY_MONITORS","reference","PREVIOUS_OPEN_DAY_MA60",
        "stocks",mapper.status(dates.today()));}
}
