package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;

import java.util.Objects;

/**
 * The {@code MetadataOptions} block that {@code AWS::EC2::Instance} and
 * {@code AWS::EC2::LaunchTemplate}'s {@code LaunchTemplateData} share. A launch template stores it
 * unchecked, as EC2 does; the instance provisioner checks the options it is about to apply with
 * EC2's own rules before it launches or changes anything.
 */
final class Ec2CfnMetadataOptions {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Ec2CfnMetadataOptions() {}

    /**
     * The options a resolved block names, with absent or blank members left null, or null when
     * there is no block. A null member takes the launch default when an instance launches, and is
     * left as the instance has it on an update (see {@link #toApply}). {@code HttpPutResponseHopLimit}
     * may be a JSON number or a numeric string, as a template parameter supplies it.
     */
    static LaunchTemplateData.MetadataOptions parse(JsonNode block) {
        if (block == null || !block.isObject() || block.isEmpty()) {
            return null;
        }
        LaunchTemplateData.MetadataOptions options = new LaunchTemplateData.MetadataOptions();
        options.setHttpTokens(member(block, "HttpTokens"));
        options.setHttpEndpoint(member(block, "HttpEndpoint"));
        options.setHttpProtocolIpv6(member(block, "HttpProtocolIpv6"));
        options.setInstanceMetadataTags(member(block, "InstanceMetadataTags"));
        String hopLimit = member(block, "HttpPutResponseHopLimit");
        if (hopLimit != null) {
            try {
                options.setHttpPutResponseHopLimit(Integer.valueOf(hopLimit));
            } catch (NumberFormatException e) {
                throw new AwsException("ValidationError", "HttpPutResponseHopLimit must be an integer.", 400);
            }
        }
        return options;
    }

    /** {@code declared} over {@code base}, member by member; either may be null. */
    static LaunchTemplateData.MetadataOptions over(LaunchTemplateData.MetadataOptions base,
                                                   LaunchTemplateData.MetadataOptions declared) {
        if (base == null) {
            return declared;
        }
        return LaunchTemplateData.MetadataOptions.merge(base, declared);
    }

    /**
     * What an update applies to the instance it keeps: every option the template declares now, and
     * the launch default for any it declared last time but no longer does, so turning an option off
     * in the template turns it off on the instance. Options it never declared are left alone. Null
     * when there is nothing to apply.
     */
    static LaunchTemplateData.MetadataOptions toApply(LaunchTemplateData.MetadataOptions previouslyDeclared,
                                                      LaunchTemplateData.MetadataOptions declared) {
        if (previouslyDeclared == null) {
            return declared;
        }
        LaunchTemplateData.MetadataOptions defaults = LaunchTemplateData.MetadataOptions.launchDefaults();
        LaunchTemplateData.MetadataOptions reset = new LaunchTemplateData.MetadataOptions();
        if (previouslyDeclared.getHttpTokens() != null) {
            reset.setHttpTokens(defaults.getHttpTokens());
        }
        if (previouslyDeclared.getHttpPutResponseHopLimit() != null) {
            reset.setHttpPutResponseHopLimit(defaults.getHttpPutResponseHopLimit());
        }
        if (previouslyDeclared.getHttpEndpoint() != null) {
            reset.setHttpEndpoint(defaults.getHttpEndpoint());
        }
        if (previouslyDeclared.getHttpProtocolIpv6() != null) {
            reset.setHttpProtocolIpv6(defaults.getHttpProtocolIpv6());
        }
        if (previouslyDeclared.getInstanceMetadataTags() != null) {
            reset.setInstanceMetadataTags(defaults.getInstanceMetadataTags());
        }
        return LaunchTemplateData.MetadataOptions.merge(reset, declared);
    }

    /** Whether applying {@code declared} would change any option {@code current} has. */
    static boolean changes(LaunchTemplateData.MetadataOptions current, LaunchTemplateData.MetadataOptions declared) {
        LaunchTemplateData.MetadataOptions applied = LaunchTemplateData.MetadataOptions.merge(current, declared);
        return !Objects.equals(current.getHttpTokens(), applied.getHttpTokens())
                || !Objects.equals(current.getHttpPutResponseHopLimit(), applied.getHttpPutResponseHopLimit())
                || !Objects.equals(current.getHttpEndpoint(), applied.getHttpEndpoint())
                || !Objects.equals(current.getHttpProtocolIpv6(), applied.getHttpProtocolIpv6())
                || !Objects.equals(current.getInstanceMetadataTags(), applied.getInstanceMetadataTags());
    }

    /** The declared options as a resource attribute value, or null when none were declared. */
    static String encode(LaunchTemplateData.MetadataOptions declared) {
        if (declared == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(declared);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not record the declared MetadataOptions", e);
        }
    }

    /** The options {@link #encode} recorded, or null when there is no record. */
    static LaunchTemplateData.MetadataOptions decode(String recorded) {
        if (recorded == null || recorded.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(recorded, LaunchTemplateData.MetadataOptions.class);
        } catch (JsonProcessingException ignored) {
            // Only encode() writes this attribute; a value it did not write resets nothing.
            return null;
        }
    }

    private static String member(JsonNode block, String name) {
        JsonNode value = block.get(name);
        if (value == null || value.isNull() || value.isContainerNode()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }
}
