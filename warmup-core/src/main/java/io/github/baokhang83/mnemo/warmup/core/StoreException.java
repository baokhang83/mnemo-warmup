package io.github.baokhang83.mnemo.warmup.core;

/** The archive store could not be reached, refused the request, or returned bytes that fail verification. */
public class StoreException extends RuntimeException {

    public StoreException(String message) {
        super(message);
    }

    public StoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
