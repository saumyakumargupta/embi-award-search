package io.github.saumya.portfolio.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Small, null-safe helpers for reading loosely structured upstream JSON.
 *
 * <p>Europe PMC JSON is partly generated from XML, so a repeatable element can arrive either as an
 * array or as a single object, text can arrive as a plain string or as {@code {"$": "..."}}, and
 * field-name casing is not something we want to depend on.
 */
final class Json {

    private Json() {
    }

    /** Returns the named child, matching the exact name first and then case-insensitively. */
    static JsonNode child(JsonNode node, String name) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode exact = node.get(name);
        if (exact != null) {
            return exact;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getKey().equalsIgnoreCase(name)) {
                return field.getValue();
            }
        }
        return null;
    }

    /** Follows a path of child names. Returns null as soon as a step is missing. */
    static JsonNode path(JsonNode node, String... names) {
        JsonNode current = node;
        for (String name : names) {
            current = child(current, name);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /** Treats a node as a list: arrays are returned element by element, a single object as one item. */
    static List<JsonNode> list(JsonNode node) {
        List<JsonNode> out = new ArrayList<>();
        if (node == null || node.isNull() || node.isMissingNode()) {
            return out;
        }
        if (node.isArray()) {
            node.forEach(out::add);
        } else {
            out.add(node);
        }
        return out;
    }

    /**
     * Extracts text from a scalar, from an XML-style {@code {"$": "..."}} object, or from the first
     * element of an array. Blank values become null.
     */
    static String text(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isArray()) {
            return node.isEmpty() ? null : text(node.get(0));
        }
        if (node.isObject()) {
            JsonNode value = node.get("$");
            return value == null ? null : text(value);
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    static String text(JsonNode node, String... path) {
        return text(path(node, path));
    }
}
