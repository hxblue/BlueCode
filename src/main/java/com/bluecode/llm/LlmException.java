package com.bluecode.llm;

import java.io.IOException;

public class LlmException extends IOException {
    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
