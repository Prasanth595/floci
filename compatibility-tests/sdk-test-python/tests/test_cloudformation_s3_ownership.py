"""A bucket's ownership controls follow its template's OwnershipControls.

Applied on create and on update, removed when an update drops the property, and left alone while
no template declares them.
"""

import json

import pytest
from botocore.exceptions import ClientError


def test_ownership_controls_create_update_remove(
    cloudformation_client, s3_client, unique_name
):
    cfn = cloudformation_client
    stack = f"ownership-{unique_name}"
    modes = ["BucketOwnerEnforced", "BucketOwnerPreferred", "ObjectWriter"]
    resources = {
        f"Bucket{i}": {
            "Type": "AWS::S3::Bucket",
            "Properties": {
                "OwnershipControls": {"Rules": [{"ObjectOwnership": mode}]}
            },
        }
        for i, mode in enumerate(modes)
    }

    def wait(operation):
        cfn.get_waiter(f"stack_{operation}_complete").wait(
            StackName=stack, WaiterConfig={"Delay": 1, "MaxAttempts": 120}
        )

    def ownership(bucket):
        return s3_client.get_bucket_ownership_controls(Bucket=bucket)[
            "OwnershipControls"
        ]["Rules"][0]["ObjectOwnership"]

    cfn.create_stack(StackName=stack, TemplateBody=json.dumps({"Resources": resources}))
    try:
        wait("create")
        buckets = {
            item["LogicalResourceId"]: item["PhysicalResourceId"]
            for item in cfn.describe_stack_resources(StackName=stack)["StackResources"]
        }
        assert [ownership(buckets[f"Bucket{i}"]) for i in range(3)] == modes

        resources["Bucket0"]["Properties"]["OwnershipControls"]["Rules"][0][
            "ObjectOwnership"
        ] = "ObjectWriter"
        resources["Bucket1"]["Properties"]["OwnershipControls"]["Rules"][0][
            "ObjectOwnership"
        ] = "BucketOwnerEnforced"
        del resources["Bucket2"]["Properties"]["OwnershipControls"]
        cfn.update_stack(StackName=stack, TemplateBody=json.dumps({"Resources": resources}))
        wait("update")
        assert ownership(buckets["Bucket0"]) == "ObjectWriter"
        assert ownership(buckets["Bucket1"]) == "BucketOwnerEnforced"
        # Floci removes them, as it clears a dropped NotificationConfiguration; real AWS's answer
        # is unmeasured (docs/services/cloudformation.md, "S3 ownership controls").
        with pytest.raises(ClientError) as removed:
            ownership(buckets["Bucket2"])
        assert removed.value.response["Error"]["Code"] == "OwnershipControlsNotFoundError"

        s3_client.put_bucket_ownership_controls(
            Bucket=buckets["Bucket2"],
            OwnershipControls={"Rules": [{"ObjectOwnership": "BucketOwnerPreferred"}]},
        )
        resources["Bucket2"]["Properties"]["Tags"] = [
            {"Key": "Probe", "Value": "external-controls"}
        ]
        cfn.update_stack(StackName=stack, TemplateBody=json.dumps({"Resources": resources}))
        wait("update")
        assert ownership(buckets["Bucket2"]) == "BucketOwnerPreferred"
    finally:
        cfn.delete_stack(StackName=stack)
        wait("delete")
