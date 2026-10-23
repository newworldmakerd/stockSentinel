package com.stocksentinel.pullback;

import java.time.OffsetDateTime;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Serialize destination-specific selection and reservation in the same transaction. */
@Service
public class PullbackDigestQueue {
    private final PullbackDigestMapper mapper;
    public PullbackDigestQueue(PullbackDigestMapper mapper){this.mapper=mapper;}
    @Transactional
    public boolean enqueue(OffsetDateTime slot,String destination,Supplier<String> render) {
        if(!mapper.queueLock(destination))return false;
        return mapper.enqueue(slot,destination,render.get())==1;
    }
}
