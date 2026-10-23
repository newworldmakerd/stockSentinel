package com.stocksentinel.intraday;

/** Only fixed error codes may leave the HTTP boundary; upstream bodies can contain secrets. */
public final class MinuteFetchException extends RuntimeException {
    public final String code;
    public MinuteFetchException(String code) { super(code);this.code=code; }
}
