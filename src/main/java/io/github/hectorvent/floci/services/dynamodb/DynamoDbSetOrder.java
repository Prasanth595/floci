package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

/**
 * DynamoDB stores a set's members in its own order, not as written: strings by
 * code point, numbers by value, binaries by unsigned byte.
 */
final class DynamoDbSetOrder {

    private static final List<String> SET_TYPES = List.of("SS", "NS", "BS");
    private static final Comparator<String> STRINGS = DynamoDbSetOrder::compareCodePoints;
    private static final Comparator<String> NUMBERS = Comparator.comparing(DynamoDbSetOrder::numberOf);
    private static final Comparator<String> BINARIES =
            Comparator.comparing(DynamoDbSetOrder::bytesOf, Arrays::compareUnsigned);

    private DynamoDbSetOrder() {}

    /** Returns the members in DynamoDB's order, or the input itself when it is not an array. */
    static JsonNode sorted(String setType, JsonNode members) {
        if (!members.isArray()) {
            return members;
        }
        List<String> texts = new ArrayList<>();
        members.forEach(member -> texts.add(member.asText()));
        texts.sort(comparatorFor(setType));
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        texts.forEach(result::add);
        return result;
    }

    /** Reorders every set in the attributes in place, through maps and lists. */
    static void sortSetsIn(JsonNode attributes) {
        if (attributes == null || !attributes.isObject()) {
            return;
        }
        for (JsonNode value : attributes) {
            sortSetsInValue(value);
        }
    }

    private static void sortSetsInValue(JsonNode value) {
        if (!(value instanceof ObjectNode object)) {
            return;
        }
        for (String setType : SET_TYPES) {
            if (object.has(setType)) {
                object.set(setType, sorted(setType, object.get(setType)));
                return;
            }
        }
        if (object.has("M")) {
            sortSetsIn(object.get("M"));
        } else if (object.has("L")) {
            object.get("L").forEach(DynamoDbSetOrder::sortSetsInValue);
        }
    }

    private static Comparator<String> comparatorFor(String setType) {
        return switch (setType) {
            case "NS" -> NUMBERS;
            case "BS" -> BINARIES;
            default -> STRINGS;
        };
    }

    // String.compareTo orders by UTF-16 unit, which puts a supplementary character
    // before U+E000..U+FFFF. DynamoDB orders by code point.
    private static int compareCodePoints(String left, String right) {
        int i = 0;
        int j = 0;
        while (i < left.length() && j < right.length()) {
            int leftPoint = left.codePointAt(i);
            int rightPoint = right.codePointAt(j);
            if (leftPoint != rightPoint) {
                return Integer.compare(leftPoint, rightPoint);
            }
            i += Character.charCount(leftPoint);
            j += Character.charCount(rightPoint);
        }
        return Integer.compare(left.length() - i, right.length() - j);
    }

    // Number syntax and base64 are checked when a request is read. A member that
    // still does not parse keeps a stable place instead of failing the write.
    private static BigDecimal numberOf(String text) {
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException ignored) {
            return BigDecimal.ZERO;
        }
    }

    private static byte[] bytesOf(String base64) {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException ignored) {
            return base64.getBytes(StandardCharsets.UTF_8);
        }
    }
}
