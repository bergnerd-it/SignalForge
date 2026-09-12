package com.bergnerd.signalforge.app.market;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

public class MarketExceptions {

    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public static class QuoteUnavailableException extends RuntimeException {
        public QuoteUnavailableException(String message) {
            super(message);
        }
    }

    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public static class QuoteStaleException extends RuntimeException {
        public QuoteStaleException(String message) {
            super(message);
        }
    }
}
