package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestShapeModel;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The property types vendored from the resource schemas, and the reader's walk over the shapes the
 * generator writes: structures, lists, maps, scalars and values the schema leaves free.
 */
class CfnPropertyTypesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final RequestShapeModel MODEL = model("""
            {
              "operations": {"AWS::Test::Thing": "AWS::Test::Thing"},
              "shapes": {
                "AWS::Test::Thing": {"type": "structure", "members": {
                  "Config": "AWS::Test::Thing/Config", "Pattern": "Json", "Ratio": "Double"}},
                "AWS::Test::Thing/Config": {"type": "structure", "members": {
                  "Headers": "AWS::Test::Thing/Config.Headers", "Count": "Integer",
                  "Children": "AWS::Test::Thing/Config.Children"}},
                "AWS::Test::Thing/Config.Headers": {"type": "map", "value": "String"},
                "AWS::Test::Thing/Config.Children": {"type": "list", "member": "AWS::Test::Thing/Config"},
                "String": {"type": "string"},
                "Integer": {"type": "integer"},
                "Boolean": {"type": "boolean"},
                "Double": {"type": "double"},
                "Json": {"type": "json"}
              }
            }
            """);

    @Test
    void eachPropertyTheProvisionersReadHasItsSchemaShape() {
        assertEquals("AWS::Events::Rule.Targets", CfnPropertyTypes.propertyShape("AWS::Events::Rule", "Targets"));
        assertEquals("AWS::Lambda::Function/Environment",
                CfnPropertyTypes.propertyShape("AWS::Lambda::Function", "Environment"));
        assertEquals("AWS::Lambda::LayerVersion.CompatibleRuntimes",
                CfnPropertyTypes.propertyShape("AWS::Lambda::LayerVersion", "CompatibleRuntimes"));
        assertEquals("AWS::AutoScaling::LaunchConfiguration/MetadataOptions",
                CfnPropertyTypes.propertyShape("AWS::AutoScaling::LaunchConfiguration", "MetadataOptions"));
    }

    @Test
    void aPropertyTheVendoredFileLacksIsAnErrorOfTheBuild() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> CfnPropertyTypes.propertyShape("AWS::Events::Rule", "NoSuchProperty"));
        assertTrue(e.getMessage().contains("regen_cfn_property_types.py"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> CfnPropertyTypes.propertyShape("AWS::No::Type", "Name"));
    }

    @Test
    void aMapReadsEachValueAsItsValueShape() {
        JsonNode read = read("{\"Headers\": {\"a\": \"x\", \"b\": 5}, \"Count\": \"3\"}", "AWS::Test::Thing/Config");

        assertEquals(json("{\"Headers\": {\"a\": \"x\", \"b\": \"5\"}, \"Count\": 3}"), read);
        AwsException e = assertThrows(AwsException.class,
                () -> read("{\"Headers\": \"x\"}", "AWS::Test::Thing/Config"));
        assertEquals("Property [Headers] expected type: JSONObject, found: String", e.getMessage());
    }

    @Test
    void aStructureNestedInAListIsReadAtEachLevel() {
        AwsException e = assertThrows(AwsException.class,
                () -> read("{\"Children\": [{\"Count\": 4.5}]}", "AWS::Test::Thing/Config"));
        assertEquals("Property [Count] expected type: Integer, found: Float", e.getMessage());
    }

    @Test
    void aValueTheSchemaLeavesFreeIsResolvedAsItIs() {
        JsonNode read = read("{\"Pattern\": {\"source\": [{\"Fn::Sub\": \"app\"}]}, \"Ratio\": \"half\"}",
                "AWS::Test::Thing");

        assertEquals(json("{\"Pattern\": {\"source\": [\"app\"]}, \"Ratio\": \"half\"}"), read);
    }

    @Test
    void aValueLeftOutByNoValueIsAbsent() {
        assertNull(new CfnPropertyTypes.Reader(engine(), MODEL)
                .read("Config", json("{\"Ref\": \"AWS::NoValue\"}"), "AWS::Test::Thing/Config"));
    }

    private static JsonNode read(String value, String shape) {
        CfnPropertyTypes.Reader reader = new CfnPropertyTypes.Reader(engine(), MODEL);
        JsonNode read = reader.read("Config", json(value), shape);
        reader.finish();
        return read;
    }

    private static CloudFormationTemplateEngine engine() {
        return CloudFormationTemplateEngine.standalone("000000000000", "us-east-1", "my-stack", MAPPER, null);
    }

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(text, e);
        }
    }

    private static RequestShapeModel model(String text) {
        return RequestShapeModel.parse(json(text));
    }
}
