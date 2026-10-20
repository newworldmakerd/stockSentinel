package com.stocksentinel.advisory;

public interface AdvisoryClient {
    Advice analyze(String verifiedSignalJson) throws Exception;
}