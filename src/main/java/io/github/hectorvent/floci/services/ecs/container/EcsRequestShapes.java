package io.github.hectorvent.floci.services.ecs.container;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestShapeModel;
import io.github.hectorvent.floci.core.common.RequestShapeModel.Shape;

import java.util.Map;

/**
 * Rejects an ECS request member of the wrong JSON type with the InvalidParameterException ECS's
 * deserializer answers before it validates anything (measured with raw requests on
 * RegisterTaskDefinition, eu-west-1, 2026-10-18). The shapes come from the vendored
 * {@code aws/ecs-request-shapes.json}, generated from botocore's ECS model by
 * {@code tools/aws/regen_ecs_shapes.py}.
 *
 * <p>It walks the members Floci keeps unparsed, a container's {@code linuxParameters}. A JSON null
 * counts as an absent member, a null list element is left to the operation's own validation, and a
 * member the model does not know is ignored. Where a structure is expected, a scalar is
 * "Unexpected field type", or "Unexpected value type in payload" for a list element; where a list
 * is expected, a scalar is "Unexpected field type"; an object where a list or a scalar is expected
 * is "Start of structure or map found where not expected"; a scalar of the wrong kind is
 * "NUMBER_VALUE cannot be converted to String" and the like, except that a string where a boolean
 * is expected is read case-blind as true or false, and is otherwise "Unexpected token received
 * from parser". A list where none is expected is refused with no message at all.
 */
final class EcsRequestShapes {

    static final String RESOURCE_NAME = "aws/ecs-request-shapes.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final class Holder {
        static final RequestShapeModel MODEL = RequestShapeModel.load(RESOURCE_NAME, "ECS request shapes");
    }

    private EcsRequestShapes() {
    }

    /** Checks {@code value}, a structure member, against the shape {@code shapeName}. */
    static void check(String shapeName, Object value) {
        check(Holder.MODEL, shapeName, value);
    }

    static void check(RequestShapeModel model, String shapeName, Object value) {
        checkValue(model, model.shapes().get(shapeName), MAPPER.valueToTree(value), false);
    }

    private static void checkValue(RequestShapeModel model, Shape shape, JsonNode value, boolean listElement) {
        if (value == null || value.isNull()) {
            return;
        }
        switch (shape.type()) {
            case "structure" -> checkStructure(model, shape, value, listElement);
            case "list" -> checkList(model, shape, value);
            case "string" -> checkScalar(value, value.isTextual(), "String");
            case "integer" -> checkScalar(value, value.isNumber(), "Integer");
            case "boolean" -> checkBoolean(value);
            default -> {
            }
        }
    }

    private static void checkStructure(RequestShapeModel model, Shape shape, JsonNode value, boolean listElement) {
        if (value.isArray()) {
            throw listNotExpected();
        }
        if (!value.isObject()) {
            throw invalidParameter(listElement ? "Unexpected value type in payload" : "Unexpected field type");
        }
        for (Map.Entry<String, JsonNode> field : value.properties()) {
            String memberShape = shape.members().get(field.getKey());
            if (memberShape != null) {
                checkValue(model, model.shapes().get(memberShape), field.getValue(), false);
            }
        }
    }

    private static void checkList(RequestShapeModel model, Shape shape, JsonNode value) {
        if (value.isObject()) {
            throw objectNotExpected();
        }
        if (!value.isArray()) {
            throw invalidParameter("Unexpected field type");
        }
        Shape element = model.shapes().get(shape.element());
        for (JsonNode item : value) {
            checkValue(model, element, item, true);
        }
    }

    private static void checkBoolean(JsonNode value) {
        if (value.isTextual()) {
            String text = value.textValue();
            if (!"true".equalsIgnoreCase(text) && !"false".equalsIgnoreCase(text)) {
                throw invalidParameter("Unexpected token received from parser");
            }
            return;
        }
        checkScalar(value, value.isBoolean(), "Boolean");
    }

    private static void checkScalar(JsonNode value, boolean matches, String type) {
        if (matches) {
            return;
        }
        if (value.isObject()) {
            throw objectNotExpected();
        }
        if (value.isArray()) {
            throw listNotExpected();
        }
        String token = value.isBoolean() ? (value.booleanValue() ? "TRUE_VALUE" : "FALSE_VALUE")
                : value.isNumber() ? "NUMBER_VALUE" : "STRING_VALUE";
        throw invalidParameter(token + " cannot be converted to " + type);
    }

    private static AwsException objectNotExpected() {
        return invalidParameter("Start of structure or map found where not expected");
    }

    /** ECS answers a list where none is expected with the exception type alone. */
    private static AwsException listNotExpected() {
        return invalidParameter(null);
    }

    private static AwsException invalidParameter(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }
}
