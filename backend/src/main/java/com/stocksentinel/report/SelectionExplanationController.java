package com.stocksentinel.report;

import java.time.LocalDate;
import com.stocksentinel.jobs.TradingDatePolicy;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/screening/explanations")
public class SelectionExplanationController {
    private final SelectionExplanationService explanations;private final TradingDatePolicy dates;
    public SelectionExplanationController(SelectionExplanationService explanations,TradingDatePolicy dates){this.explanations=explanations;this.dates=dates;}
    @GetMapping("/{symbol}") public Object explain(@PathVariable String symbol,@RequestParam(required=false) LocalDate tradeDate){
        return explanations.explain(symbol,tradeDate==null?dates.today():tradeDate);
    }
}
