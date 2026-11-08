package me.wani4ka.concurrency.review;

import java.util.concurrent.TimeoutException;

public class MarketplaceException extends RuntimeException {

    MarketplaceException(String message, Throwable cause) {
        super(message, cause);
    }

    public boolean isTimeout() {
        return getCause() instanceof TimeoutException;
    }
}
