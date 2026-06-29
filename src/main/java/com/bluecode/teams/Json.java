package com.bluecode.teams;

import com.fasterxml.jackson.databind.ObjectMapper;

final class Json {
    static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"error\":\"json encode failed\"}";
        }
    }
}
