package com.bluecode.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ToolSchemas {
    private ToolSchemas() {
    }

    static Map<String, Object> object(Map<String, Object> properties, String... required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of(required));
        return schema;
    }

    static Map<String, Object> properties(Object... values) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 3) {
            properties.put((String) values[i], stringProperty((String) values[i + 1], (String) values[i + 2]));
        }
        return properties;
    }

    private static Map<String, Object> stringProperty(String description, String example) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "string");
        property.put("description", description);
        if (example != null && !example.isBlank()) {
            property.put("example", example);
        }
        return property;
    }
}
