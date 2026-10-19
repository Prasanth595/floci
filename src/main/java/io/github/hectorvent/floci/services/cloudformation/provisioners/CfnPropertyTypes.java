package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestShapeModel;
import io.github.hectorvent.floci.core.common.RequestShapeModel.Shape;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * CloudFormation's checks of a property value against the type the resource schema declares, with
 * the messages they give (measured in eu-west-1, 2026-10-18). The types come from the vendored
 * {@code aws/cfn-property-types.json}, generated from the published resource schemas by
 * {@code tools/aws/regen_cfn_property_types.py}.
 *
 * <p>Template validation runs over the whole template before any resource is touched and names the
 * property, or the list index, alone: "Property [Layers] expected type: JSONArray, found: String".
 * Where an object, a map or a list is expected, a number or a boolean reads as a String. Where text
 * is expected, any scalar is taken as its text. Where an integer is expected, a whole number or text
 * that spells one is taken, and anything else is named by what it is: Float (4.5 or "4.5"),
 * Boolean, String, JSONObject, JSONArray. Where a boolean is expected, only an object or a list is
 * refused there. A list of strings also takes every intrinsic that yields a list, as
 * {@link #readStringList} says. A number, and a value the schema leaves free (a free-form object,
 * several types at once), are taken as they are.
 *
 * <p>The resource handler's model validation runs after it, on the values CloudFormation converted,
 * and names the whole path: a boolean that is not true or false in any case, and an integer beyond
 * 32 bits, fail as "Model validation failed (#/Targets/0/EcsParameters/EnableECSManagedTags:
 * expected type: Boolean, found: String)".
 */
final class CfnPropertyTypes {

    static final String RESOURCE_NAME = "aws/cfn-property-types.json";

    private static final Pattern WHOLE_NUMBER = Pattern.compile("-?\\d+");
    private static final String INTEGER_RANGE_HINT = ". This may occur when a numeric value exceeds 32-bit"
            + " integer max (2147483647). Consider using 'number' type or ensure value fits in integer range.";

    private static final class Holder {
        static final RequestShapeModel MODEL = RequestShapeModel.load(RESOURCE_NAME, "CloudFormation property types");
    }

    private CfnPropertyTypes() {
    }

    /** The shape the resource schema of {@code resourceType} declares for its property {@code property}. */
    static String propertyShape(String resourceType, String property) {
        String properties = Holder.MODEL.operations().get(resourceType);
        String shape = properties == null ? null : Holder.MODEL.shapes().get(properties).members().get(property);
        if (shape == null) {
            throw new IllegalStateException(resourceType + "." + property + " is not in " + RESOURCE_NAME
                    + "; add the type to tools/aws/regen_cfn_property_types.py");
        }
        return shape;
    }

    /**
     * Refuses a value of another type than {@code expected}, an object or a list, with template
     * validation's message, where a number or a boolean reads as a String.
     */
    static void requireType(String property, JsonNode value, JsonNodeType expected) {
        if (value.getNodeType() != expected) {
            throw templateMismatch(property, expected == JsonNodeType.OBJECT ? "JSONObject" : "JSONArray",
                    containerName(value));
        }
    }

    /**
     * The list-of-strings property written as {@code raw} in the template, or null when it is
     * {@code Ref: AWS::NoValue}, directly or through {@code Fn::If}. A literal of another type is
     * refused (see {@link #requireType}), and so is an intrinsic that yields a string (see
     * {@link CloudFormationTemplateEngine#yieldsString}). Any other intrinsic is read through
     * {@link CloudFormationTemplateEngine#resolveStringList}, as CloudFormation reads a {@code Ref}
     * to a {@code CommaDelimitedList} parameter, which the engine resolves to the comma-joined text,
     * and {@code Fn::Split} (measured, eu-west-1, 2026-10-18).
     */
    static List<String> readStringList(String property, JsonNode raw, CloudFormationTemplateEngine engine) {
        if (raw.isValueNode()) {
            requireType(property, raw, JsonNodeType.ARRAY);
        }
        if (raw.isObject()) {
            JsonNode resolved = engine.resolveNodeOmittingNoValue(raw);
            if (resolved == null || resolved.isNull() || resolved.isMissingNode()) {
                return null;
            }
            if (resolved.isObject() || (!resolved.isArray() && engine.yieldsString(raw))) {
                requireType(property, resolved, JsonNodeType.ARRAY);
            }
        }
        return engine.resolveStringList(raw);
    }

    /**
     * Reads template values against the shapes the resource schemas declare and resolves them,
     * converting them as CloudFormation does before the handler sees them: an integer or a boolean
     * given as text becomes one, and a number or a boolean where text is expected becomes text.
     * Members a shape does not declare are resolved as they are. What {@code AWS::NoValue} stands for
     * is left out. Template validation failures are thrown at once; the first model validation
     * failure waits for {@link #finish}, so every value read has passed template validation first,
     * as on AWS.
     */
    static final class Reader {

        private final CloudFormationTemplateEngine engine;
        private final RequestShapeModel model;
        private AwsException modelFailure;

        Reader(CloudFormationTemplateEngine engine) {
            this(engine, Holder.MODEL);
        }

        Reader(CloudFormationTemplateEngine engine, RequestShapeModel model) {
            this.engine = engine;
            this.model = model;
        }

        /**
         * The template value {@code raw}, at {@code path} in the resource's properties, read as the
         * shape {@code shapeName}; null when it is absent or {@code AWS::NoValue}.
         */
        JsonNode read(String path, JsonNode raw, String shapeName) {
            return read(path, path.substring(path.lastIndexOf('/') + 1), engine.omitNoValue(raw), shapeName);
        }

        /** Throws the first model validation failure found by {@link #read}, if any. */
        void finish() {
            if (modelFailure != null) {
                throw modelFailure;
            }
        }

        private JsonNode read(String path, String property, JsonNode raw, String shapeName) {
            if (raw == null || raw.isNull() || raw.isMissingNode()) {
                return null;
            }
            Shape shape = model.shapes().get(shapeName);
            if ("list".equals(shape.type()) && "string".equals(model.shapes().get(shape.element()).type())) {
                return readStringList(property, raw);
            }
            JsonNode value = raw.isContainerNode() && !CloudFormationTemplateEngine.callsIntrinsic(raw)
                    ? raw : engine.resolveNode(raw);
            if (value == null || value.isNull() || value.isMissingNode()) {
                return null;
            }
            return switch (shape.type()) {
                case "structure" -> readObject(path, property, value, shape.members());
                case "map" -> readMap(path, property, value, shape.element());
                case "list" -> readList(path, property, value, shape.element());
                case "string" -> readString(property, value);
                case "integer" -> readInteger(path, property, value);
                case "boolean" -> readBoolean(path, property, value);
                default -> value == raw ? engine.resolveNode(raw) : value;
            };
        }

        private JsonNode readObject(String path, String property, JsonNode value, Map<String, String> members) {
            requireType(property, value, JsonNodeType.OBJECT);
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<String, JsonNode> member : value.properties()) {
                String memberShape = members.get(member.getKey());
                JsonNode read = memberShape == null ? engine.resolveNode(member.getValue())
                        : read(path + "/" + member.getKey(), member.getKey(), member.getValue(), memberShape);
                if (read != null) {
                    out.set(member.getKey(), read);
                }
            }
            return out;
        }

        private JsonNode readMap(String path, String property, JsonNode value, String valueShape) {
            requireType(property, value, JsonNodeType.OBJECT);
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<String, JsonNode> entry : value.properties()) {
                JsonNode read = read(path + "/" + entry.getKey(), entry.getKey(), entry.getValue(), valueShape);
                if (read != null) {
                    out.set(entry.getKey(), read);
                }
            }
            return out;
        }

        private JsonNode readList(String path, String property, JsonNode value, String itemShape) {
            requireType(property, value, JsonNodeType.ARRAY);
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            for (int i = 0; i < value.size(); i++) {
                JsonNode read = read(path + "/" + i, String.valueOf(i), value.get(i), itemShape);
                if (read != null) {
                    out.add(read);
                }
            }
            return out;
        }

        /** A literal item that is an object or a list is refused by its index, as for any string. */
        private JsonNode readStringList(String property, JsonNode raw) {
            if (raw.isArray()) {
                for (int i = 0; i < raw.size(); i++) {
                    JsonNode item = raw.get(i);
                    if (item.isContainerNode() && !CloudFormationTemplateEngine.callsIntrinsic(item)) {
                        throw templateMismatch(String.valueOf(i), "String", containerName(item));
                    }
                }
            }
            List<String> values = CfnPropertyTypes.readStringList(property, raw, engine);
            if (values == null) {
                return null;
            }
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            values.forEach(out::add);
            return out;
        }

        private static JsonNode readString(String property, JsonNode value) {
            if (value.isContainerNode()) {
                throw templateMismatch(property, "String", containerName(value));
            }
            return value.isTextual() ? value : TextNode.valueOf(value.asText());
        }

        private JsonNode readInteger(String path, String property, JsonNode value) {
            String text = value.isIntegralNumber() ? value.bigIntegerValue().toString() : value.asText();
            if (value.isContainerNode() || value.isBoolean() || !WHOLE_NUMBER.matcher(text).matches()) {
                throw templateMismatch(property, "Integer", scalarName(value));
            }
            BigDecimal number = new BigDecimal(text);
            if (number.compareTo(BigDecimal.valueOf(Integer.MIN_VALUE)) < 0
                    || number.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
                recordModelFailure(path, "Integer", INTEGER_RANGE_HINT);
                return value;
            }
            return IntNode.valueOf(number.intValue());
        }

        private JsonNode readBoolean(String path, String property, JsonNode value) {
            if (value.isContainerNode()) {
                throw templateMismatch(property, "Boolean", containerName(value));
            }
            String text = value.asText();
            if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
                return BooleanNode.valueOf(Boolean.parseBoolean(text));
            }
            recordModelFailure(path, "Boolean", "");
            return value;
        }

        private void recordModelFailure(String path, String expected, String hint) {
            if (modelFailure == null) {
                modelFailure = new AwsException("InvalidRequest", "Model validation failed (#/" + path
                        + ": expected type: " + expected + ", found: String" + hint + ")", 400);
            }
        }
    }

    private static AwsException templateMismatch(String property, String expected, String found) {
        return new AwsException("ValidationError", "Property [" + property + "] expected type: "
                + expected + ", found: " + found, 400);
    }

    private static String containerName(JsonNode value) {
        return switch (value.getNodeType()) {
            case OBJECT -> "JSONObject";
            case ARRAY -> "JSONArray";
            default -> "String";
        };
    }

    /** What template validation calls a value that is not a whole number where an integer is expected. */
    private static String scalarName(JsonNode value) {
        if (value.isContainerNode()) {
            return containerName(value);
        }
        if (value.isBoolean()) {
            return "Boolean";
        }
        if (value.isNumber()) {
            return "Float";
        }
        try {
            new BigDecimal(value.asText());
            return "Float";
        } catch (NumberFormatException notANumber) {
            return "String";
        }
    }
}
