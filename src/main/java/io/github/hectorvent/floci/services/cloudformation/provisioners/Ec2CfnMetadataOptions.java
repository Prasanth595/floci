package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;

import java.util.Objects;

/**
 * The {@code MetadataOptions} block that {@code AWS::EC2::Instance} and
 * {@code AWS::EC2::LaunchTemplate}'s {@code LaunchTemplateData} share. Its values are checked where
 * EC2 checks them, when an instance launches with them.
 */
final class Ec2CfnMetadataOptions {

    private Ec2CfnMetadataOptions() {}

    /**
     * The options a resolved block names, with absent or blank members left null so the launch
     * default applies to them, or null when there is no block. {@code HttpPutResponseHopLimit} may
     * be a JSON number or a numeric string, as a template parameter supplies it.
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

    /** Whether applying {@code declared} would change any option {@code current} has. */
    static boolean changes(LaunchTemplateData.MetadataOptions current, LaunchTemplateData.MetadataOptions declared) {
        LaunchTemplateData.MetadataOptions applied = LaunchTemplateData.MetadataOptions.merge(current, declared);
        return !Objects.equals(current.getHttpTokens(), applied.getHttpTokens())
                || !Objects.equals(current.getHttpPutResponseHopLimit(), applied.getHttpPutResponseHopLimit())
                || !Objects.equals(current.getHttpEndpoint(), applied.getHttpEndpoint())
                || !Objects.equals(current.getHttpProtocolIpv6(), applied.getHttpProtocolIpv6())
                || !Objects.equals(current.getInstanceMetadataTags(), applied.getInstanceMetadataTags());
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
