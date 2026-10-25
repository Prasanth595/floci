package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static io.github.hectorvent.floci.services.ec2.Ec2KeyPairFixtures.ED25519_KEY;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TagSpecification on the create actions that used to drop it: CreateKeyPair, ImportKeyPair,
 * CreateNetworkAcl, RegisterImage, CreateImage and CopyImage. AWS applies each specification to
 * the resource the call creates, the next Describe* reports the tags, CreateKeyPair and
 * ImportKeyPair also return them in the create response's tagSet, and CreateImage and CopyImage
 * tag the AMI's new snapshots as well as the AMI.
 */
@QuarkusTest
class Ec2CreateTagSpecificationIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261025/us-east-1/ec2/aws4_request";

    @Test
    void createKeyPairAppliesItsTagSpecificationAndDescribesIt() {
        XmlPath created = ec2("CreateKeyPair",
                "KeyName", "tagged-created-" + UUID.randomUUID(),
                "TagSpecification.1.ResourceType", "key-pair",
                "TagSpecification.1.Tag.1.Key", "Team",
                "TagSpecification.1.Tag.1.Value", "keys");
        String id = created.getString("CreateKeyPairResponse.keyPairId");
        try {
            assertEquals("Team", created.getString("CreateKeyPairResponse.tagSet.item.key"));
            assertEquals("keys", created.getString("CreateKeyPairResponse.tagSet.item.value"));

            XmlPath described = ec2("DescribeKeyPairs", "KeyPairId.1", id);
            assertEquals("Team", described.getString("DescribeKeyPairsResponse.keySet.item.tagSet.item.key"));
            assertEquals("keys", described.getString("DescribeKeyPairsResponse.keySet.item.tagSet.item.value"));
        } finally {
            ec2("DeleteKeyPair", "KeyPairId", id);
        }
    }

    @Test
    void importKeyPairAppliesItsTagSpecificationAndDescribesIt() {
        XmlPath imported = ec2("ImportKeyPair",
                "KeyName", "tagged-imported-" + UUID.randomUUID(),
                "PublicKeyMaterial", Base64.getEncoder()
                        .encodeToString(ED25519_KEY.getBytes(StandardCharsets.UTF_8)),
                "TagSpecification.1.ResourceType", "key-pair",
                "TagSpecification.1.Tag.1.Key", "Team",
                "TagSpecification.1.Tag.1.Value", "keys");
        String id = imported.getString("ImportKeyPairResponse.keyPairId");
        try {
            assertEquals("Team", imported.getString("ImportKeyPairResponse.tagSet.item.key"));
            assertEquals("keys", imported.getString("ImportKeyPairResponse.tagSet.item.value"));

            XmlPath described = ec2("DescribeKeyPairs", "KeyPairId.1", id);
            assertEquals("Team", described.getString("DescribeKeyPairsResponse.keySet.item.tagSet.item.key"));
            assertEquals("keys", described.getString("DescribeKeyPairsResponse.keySet.item.tagSet.item.value"));
        } finally {
            ec2("DeleteKeyPair", "KeyPairId", id);
        }
    }

    @Test
    void createNetworkAclAppliesItsTagSpecificationAndDescribesIt() {
        String vpc = ec2("CreateVpc", "CidrBlock", "10.60.0.0/16")
                .getString("CreateVpcResponse.vpc.vpcId");
        String acl = null;
        try {
            XmlPath created = ec2("CreateNetworkAcl",
                    "VpcId", vpc,
                    "TagSpecification.1.ResourceType", "network-acl",
                    "TagSpecification.1.Tag.1.Key", "Team",
                    "TagSpecification.1.Tag.1.Value", "net");
            acl = created.getString("CreateNetworkAclResponse.networkAcl.networkAclId");
            assertEquals("Team", created.getString("CreateNetworkAclResponse.networkAcl.tagSet.item.key"));
            assertEquals("net", created.getString("CreateNetworkAclResponse.networkAcl.tagSet.item.value"));

            XmlPath described = ec2("DescribeNetworkAcls", "NetworkAclId.1", acl);
            assertEquals("Team", described.getString("DescribeNetworkAclsResponse.networkAclSet.item.tagSet.item.key"));
            assertEquals("net", described.getString("DescribeNetworkAclsResponse.networkAclSet.item.tagSet.item.value"));
        } finally {
            if (acl != null) {
                ec2("DeleteNetworkAcl", "NetworkAclId", acl);
            }
            ec2("DeleteVpc", "VpcId", vpc);
        }
    }

    @Test
    void registerImageAppliesItsTagSpecificationAndDescribesIt() {
        String imageId = ec2("RegisterImage",
                "Name", "tagged-registered-" + UUID.randomUUID(),
                "Architecture", "x86_64",
                "RootDeviceName", "/dev/xvda",
                "TagSpecification.1.ResourceType", "image",
                "TagSpecification.1.Tag.1.Key", "Team",
                "TagSpecification.1.Tag.1.Value", "images")
                .getString("RegisterImageResponse.imageId");
        try {
            XmlPath described = describeImage(imageId);
            assertEquals("Team", described.getString("DescribeImagesResponse.imagesSet.item.tagSet.item.key"));
            assertEquals("images", described.getString("DescribeImagesResponse.imagesSet.item.tagSet.item.value"));
        } finally {
            ec2("DeregisterImage", "ImageId", imageId);
        }
    }

    @Test
    void copyImageAppliesItsSpecificationsToTheCopyAndItsNewSnapshots() {
        String sourceSnapshot = "snap-0123456789abcdef0";
        String source = ec2("RegisterImage",
                "Name", "tag-copy-source-" + UUID.randomUUID(),
                "Architecture", "x86_64",
                "RootDeviceName", "/dev/xvda",
                "BlockDeviceMapping.1.DeviceName", "/dev/xvda",
                "BlockDeviceMapping.1.Ebs.SnapshotId", sourceSnapshot,
                "BlockDeviceMapping.1.Ebs.VolumeSize", "8")
                .getString("RegisterImageResponse.imageId");
        String copy = null;
        try {
            copy = ec2("CopyImage",
                    "SourceRegion", "us-east-1",
                    "SourceImageId", source,
                    "Name", "tag-copy-destination-" + UUID.randomUUID(),
                    "TagSpecification.1.ResourceType", "image",
                    "TagSpecification.1.Tag.1.Key", "Team",
                    "TagSpecification.1.Tag.1.Value", "images",
                    "TagSpecification.2.ResourceType", "snapshot",
                    "TagSpecification.2.Tag.1.Key", "Team",
                    "TagSpecification.2.Tag.1.Value", "snapshots")
                    .getString("CopyImageResponse.imageId");

            XmlPath described = describeImage(copy);
            assertEquals("Team", described.getString("DescribeImagesResponse.imagesSet.item.tagSet.item.key"));
            assertEquals("images", described.getString("DescribeImagesResponse.imagesSet.item.tagSet.item.value"));
            List<String> copySnapshots = snapshotIdsOf(described);
            assertEquals(1, copySnapshots.size());
            assertFalse(copySnapshots.contains(sourceSnapshot), "the copy takes its own fresh snapshots");

            XmlPath snapshot = ec2("DescribeSnapshots", "SnapshotId.1", copySnapshots.get(0));
            assertEquals("Team", snapshot.getString("DescribeSnapshotsResponse.snapshotSet.item.tagSet.item.key"));
            assertEquals("snapshots",
                    snapshot.getString("DescribeSnapshotsResponse.snapshotSet.item.tagSet.item.value"));
            XmlPath sourceDescribed = ec2("DescribeSnapshots", "SnapshotId.1", sourceSnapshot);
            assertTrue(sourceDescribed.getList("DescribeSnapshotsResponse.snapshotSet.item.tagSet.item",
                    String.class).isEmpty(), "the source AMI's own snapshot is left untagged");
        } finally {
            if (copy != null) {
                ec2("DeregisterImage", "ImageId", copy, "DeleteAssociatedSnapshots", "true");
            }
            ec2("DeregisterImage", "ImageId", source);
        }
    }

    @Test
    void createImageAppliesItsSpecificationsToTheAmiAndItsSnapshots() {
        String instance = ec2("RunInstances",
                "ImageId", "ami-0abcdef1234567890",
                "InstanceType", "t3.micro",
                "MinCount", "1",
                "MaxCount", "1")
                .getString("RunInstancesResponse.instancesSet.item.instanceId");
        String imageId = null;
        try {
            imageId = ec2("CreateImage",
                    "InstanceId", instance,
                    "Name", "tag-captured-" + UUID.randomUUID(),
                    "NoReboot", "true",
                    "TagSpecification.1.ResourceType", "image",
                    "TagSpecification.1.Tag.1.Key", "Team",
                    "TagSpecification.1.Tag.1.Value", "images",
                    "TagSpecification.2.ResourceType", "snapshot",
                    "TagSpecification.2.Tag.1.Key", "Team",
                    "TagSpecification.2.Tag.1.Value", "snapshots")
                    .getString("CreateImageResponse.imageId");

            XmlPath described = describeImage(imageId);
            assertEquals("Team", described.getString("DescribeImagesResponse.imagesSet.item.tagSet.item.key"));
            assertEquals("images", described.getString("DescribeImagesResponse.imagesSet.item.tagSet.item.value"));
            assertFalse(snapshotIdsOf(described).isEmpty(), "the captured AMI records its new snapshots");
            for (String snapshotId : snapshotIdsOf(described)) {
                XmlPath snapshot = ec2("DescribeSnapshots", "SnapshotId.1", snapshotId);
                assertEquals("Team", snapshot.getString("DescribeSnapshotsResponse.snapshotSet.item.tagSet.item.key"));
                assertEquals("snapshots",
                        snapshot.getString("DescribeSnapshotsResponse.snapshotSet.item.tagSet.item.value"));
            }
        } finally {
            if (imageId != null) {
                ec2("DeregisterImage", "ImageId", imageId, "DeleteAssociatedSnapshots", "true");
            }
            ec2("TerminateInstances", "InstanceId.1", instance);
        }
    }

    private XmlPath describeImage(String imageId) {
        return ec2("DescribeImages", "ImageId.1", imageId);
    }

    private List<String> snapshotIdsOf(XmlPath describedImage) {
        return describedImage.getList(
                "DescribeImagesResponse.imagesSet.item.blockDeviceMapping.item.ebs.snapshotId", String.class);
    }

    private XmlPath ec2(String action, String... formParams) {
        RequestSpecification request = given().header("Authorization", AUTH).formParam("Action", action);
        for (int i = 0; i < formParams.length; i += 2) {
            request.formParam(formParams[i], formParams[i + 1]);
        }
        return request.post("/").then().statusCode(200).extract().xmlPath();
    }
}
