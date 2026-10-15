# Lambda Web Functions

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/2025-03-07/web-functions/...`

The `Lambda Web` service model (apiVersion 2025-03-07, `aws lambda-web` in the AWS CLI) signs as `lambda`, so it is served under Floci's existing lambda service and is enabled by it. A Web Function owns immutable revisions (code and configuration) and endpoints (a domain name that routes to one or two revisions).

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateWebFunction` | Create a function with its first revision and endpoint |
| `ListWebFunctions` | List functions by name; `state` is the only filter |
| `GetWebFunction` | Function detail |
| `DeleteWebFunction` | Delete a function with its revisions and endpoints |
| `CreateWebFunctionRevision` | Create a revision from an S3 object; endpoints in `LatestRevision` mode move to it |
| `ListWebFunctionRevisions` | List revisions, newest first |
| `GetWebFunctionRevision` | Revision detail with the defaults the service fills in |
| `DeleteWebFunctionRevision` | Delete a revision no endpoint routes to |
| `CreateWebFunctionEndpoint` | Create a `HomeRegion`, `MultiRegion` or `PerRegion` endpoint |
| `ListWebFunctionEndpoints` | List endpoints by name |
| `GetWebFunctionEndpoint` | Endpoint detail with its per-region block |
| `UpdateWebFunctionEndpoint` | Change auth type, description, auto-deployment mode, revision weights, scaling and throttling |
| `DeleteWebFunctionEndpoint` | Delete an endpoint |
| `GetWebAccountSettings` | Account quotas and the function count |
| `ListTags` | Tags of a function |
| `TagResource` | Tag a function |
| `UntagResource` | Untag a function |
| `PutResourcePolicy` | Set the function's resource policy with an optimistic `revisionId` |
| `GetResourcePolicy` | Read the resource policy |
| `DeleteResourcePolicy` | Delete the resource policy |
<!-- floci:actions:end -->

## Emulation Behavior

- **Instant convergence:** a revision and an endpoint are stored `Active` and an endpoint update is stored `Successful`. The create and update responses still report the transitional state AWS reports (`Pending`, `updateStatus: InProgress`, and the per-region block as it was before the update), so a waiter sees one poll.
- **Code comes from S3 only.** The object named in `buildConfig.codeConfig.s3Object` must exist in Floci's S3, as on AWS. AWS also requires bucket versioning and a bucket policy for the Lambda service principal. Floci checks neither, and the revision does not pin a `versionId` the request did not give.
- **Execution role and network connector** ARNs are checked for shape only. AWS also checks that it can assume the role.
- **Resource policies** are checked the way AWS checks them before storing: the document grammar, a `lambda:` action, a `Resource` that names the function, a valid principal, and no public `Allow` without a trusted condition. The policy is not evaluated on requests.
- **ARNs** given as identifiers must name the request's region and the caller's account, as on AWS. A revision or endpoint ARN resolves by its id or name only.
- **Domain names** take the form `<id>.001.lambda-web.<region>.<floci host>` and `<id>.lambda-web.global.<floci host>`, with the host and port of `floci.base-url`. A `PerRegion` endpoint gets one domain per region in `regionalEndpoints`.
- **Not emulated:** invoking a function through its endpoint domain, execution environments, revision auto-cleanup past 50 revisions, and `IamAuth`. The endpoint domain does not answer requests yet.

### `aws lambda-web deploy`

The CLI's `deploy` command works against Floci with `AWS_ENDPOINT_URL` set:

```bash
AWS_ENDPOINT_URL=http://localhost:4566 aws lambda-web deploy --name my-app --hello-world --create
```

The command passes `--endpoint-url` only to the Lambda Web client. Its STS, S3 and IAM calls use the default endpoints, so the environment variable is what routes them to Floci. The `endpointUrl` it prints is the Floci domain name behind `https://`, which Floci does not serve.

## CloudFormation

`AWS::Lambda::WebFunction`, `AWS::Lambda::WebFunctionRevision` and `AWS::Lambda::WebFunctionEndpoint` provision through the resource registry. `Ref` of each is its ARN. Every property of a revision is createOnly, so a changed revision mints a new one and the displaced revision is deleted once the update commits. An endpoint keeps its identity while its name, type, regions and function stay the same. `Fn::GetAtt` on `RegionalEndpoints` is not supported.
