package com.bluecode.llm;

public class PromptTooLongException extends LlmException {
    public PromptTooLongException(Throwable cause) {
        super("prompt too long for context window", cause);
    }
}
