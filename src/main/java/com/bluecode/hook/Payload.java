package com.bluecode.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class Payload {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final Map<String, Object> data;

    public Payload(Map<String, Object> data) {
        this.data = Map.copyOf(data == null ? Map.of() : data);
    }

    public Map<String, Object> data() {
        return data;
    }

    public String getByPath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        Object value = data;
        for (String part : path.split("\\.")) {
            if (part.isBlank()) {
                return "";
            }
            if (value instanceof Map<?, ?> map) {
                value = map.get(part);
            } else if (value instanceof JsonNode node) {
                value = node.path(part);
            } else {
                return "";
            }
            if (value == null) {
                return "";
            }
        }
        return stringify(value);
    }

    public String toSortedJson() {
        return toJson(sortValue(data));
    }

    private static String stringify(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof JsonNode node) {
            if (node.isMissingNode() || node.isNull()) {
                return "";
            }
            if (node.isValueNode()) {
                return node.asText();
            }
            return toJson(sortValue(MAPPER.convertValue(node, Object.class)));
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            return toJson(sortValue(value));
        }
        return String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Object sortValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), sortValue(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> sorted = new ArrayList<>(list.size());
            for (Object item : list) {
                sorted.add(sortValue(item));
            }
            return sorted;
        }
        return value;
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
