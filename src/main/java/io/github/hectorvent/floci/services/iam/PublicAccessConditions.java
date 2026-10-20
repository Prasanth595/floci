package io.github.hectorvent.floci.services.iam;

import java.util.Set;

/**
 * What keeps a resource policy statement that grants to everyone from being public, for the
 * services that refuse or report a public policy. Each service decides with its own rule, the one
 * measured on it; these are the parts the rules have in common.
 */
public final class PublicAccessConditions {

    /** Condition keys that, pinned to a value, restrict a wildcard principal to known callers. */
    public static final Set<String> RESTRICTING_KEYS = Set.of(
            "aws:PrincipalAccount", "aws:PrincipalArn", "aws:PrincipalOrgID", "aws:PrincipalOrgPaths",
            "aws:SourceAccount", "aws:SourceArn", "aws:SourceOrgID", "aws:SourceOrgPaths", "aws:SourceVpc",
            "aws:SourceVpcArn", "aws:SourceVpce", "aws:VpceAccount", "aws:VpceOrgID", "aws:VpceOrgPaths",
            "aws:Ec2InstanceSourceVpc", "aws:UserId", "aws:PrincipalServiceName", "aws:PrincipalServiceNamesList",
            "aws:PrincipalIsAWSService");

    /** The keys among {@link #RESTRICTING_KEYS} whose values are ARNs. */
    public static final Set<String> ARN_KEYS = Set.of("aws:PrincipalArn", "aws:SourceArn", "aws:SourceVpcArn");

    private PublicAccessConditions() {
    }

    /** A value that names one thing: not empty, and with no wildcard or policy variable in it. */
    public static boolean isFixedValue(String value) {
        return !value.isEmpty()
                && value.indexOf('*') < 0
                && value.indexOf('?') < 0
                && !value.contains("${");
    }

    /**
     * An ARN pattern that pins the account: six fields, the account one without a wildcard. Only
     * the account matters, as a pattern in the rest of the ARN still names that account's resources.
     */
    public static boolean pinsAccount(String arnPattern) {
        String[] fields = arnPattern.split(":", 6);
        return fields.length == 6 && fields[4].indexOf('*') < 0 && fields[4].indexOf('?') < 0;
    }

    /**
     * An {@code aws:SourceIp} value narrow enough not to be public: AWS still calls a policy public
     * when it is conditioned on a range broader than /8 for IPv4 or /32 for IPv6, so only a prefix
     * at least that long restricts it. A bare address carries no prefix and is as narrow as a range
     * gets.
     */
    public static boolean isNarrowSourceIp(String cidr) {
        int slash = cidr.indexOf('/');
        if (slash < 0) {
            return isFixedValue(cidr);
        }
        String address = cidr.substring(0, slash);
        if (!isFixedValue(address)) {
            return false;
        }
        try {
            int prefixLength = Integer.parseInt(cidr.substring(slash + 1).trim());
            return address.indexOf(':') >= 0 ? prefixLength >= 32 : prefixLength >= 8;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
