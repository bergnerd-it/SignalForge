package com.bergnerd.signalforge.app.portfolio;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

public class TradeExceptions {

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public static class InsufficientFundsException extends RuntimeException {
        public InsufficientFundsException(String message) {
            super(message);
        }
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public static class InsufficientSharesException extends RuntimeException {
        public InsufficientSharesException(String message) {
            super(message);
        }
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public static class InvalidTradeException extends RuntimeException {
        public InvalidTradeException(String message) {
            super(message);
        }
    }
}
