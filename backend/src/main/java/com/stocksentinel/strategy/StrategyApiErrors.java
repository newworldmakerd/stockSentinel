package com.stocksentinel.strategy;

import com.stocksentinel.signal.SignalController;
import com.stocksentinel.stock.WatchlistController;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ProblemDetail;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes={StrategyController.class,WatchlistController.class,SignalController.class})
public class StrategyApiErrors {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> error(ResponseStatusException error) {
        var body=ProblemDetail.forStatusAndDetail(error.getStatusCode(),error.getReason());
        body.setProperty("code",error.getReason());
        return ResponseEntity.status(error.getStatusCode()).body(body);
    }
}