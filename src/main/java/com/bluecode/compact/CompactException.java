package com.bluecode.compact;

public class CompactException extends Exception {
    public CompactException(String message) {
        super(message);
    }

    public CompactException(String message, Throwable cause) {
        super(message, cause);
    }
}
