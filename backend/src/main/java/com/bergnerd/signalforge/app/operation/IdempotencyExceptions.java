package com.bergnerd.signalforge.app.operation;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

public class IdempotencyExceptions {

    @ResponseStatus(HttpStatus.CONFLICT)
    public static class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException(String message) {
            super(message);
        }
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public static class MissingIdempotencyKeyException extends RuntimeException {
        public MissingIdempotencyKeyException(String message) {
            super(message);
        }
    }
}
