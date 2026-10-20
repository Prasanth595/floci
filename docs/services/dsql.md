# Aurora DSQL

**Protocol:** REST JSON

**Endpoint:** `http://localhost:4566`

Floci serves Aurora DSQL's control plane and `AWS::DSQL::Cluster`. A cluster has no database
behind it here: it is `ACTIVE` as soon as it is created, and its endpoint is named as on AWS but
not served. The answers and messages below were measured on AWS.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateCluster` | Creates a cluster, `CREATING` in the answer and `ACTIVE` right after, or `PENDING_SETUP` for a multi-Region one; deletion protection is on unless turned off |
| `ListClusters` | Lists the Region's clusters, paginated |
| `GetCluster` | Returns a cluster, its endpoint and encryption details |
| `UpdateCluster` | Changes deletion protection, the KMS key and the multi-Region properties |
| `DeleteCluster` | Deletes a cluster, `DELETING` in the answer or `PENDING_DELETE` for a multi-Region one; refused while deletion protection is on |
| `GetVpcEndpointServiceName` | Returns the Region's VPC endpoint service name |
| `PutClusterPolicy` | Sets the cluster's resource policy once it passes DSQL's checks, with optimistic versioning |
| `GetClusterPolicy` | Returns the resource policy in DSQL's form, and its version |
| `DeleteClusterPolicy` | Removes the resource policy, answering the version removed |
<!-- floci:actions:end -->

`TagResource`, `UntagResource` and `ListTagsForResource` work on cluster ARNs. As on AWS, an ARN
that is not a well-formed cluster ARN is refused with `AccessDeniedException`, one of another
Region with `BadRequestException`, and one of no cluster with `NotFoundException`. Cluster
identifiers are 26 characters of lowercase base32, as DSQL's are, and invalid ones are refused with
DSQL's messages. A cluster's endpoint is `<cluster-id>.dsql.<region>.localhost.floci.io`.

A cluster created with a witness Region is `PENDING_SETUP`, and lists itself among its clusters.
Once it and each peer it is updated with name one another, all of them are `ACTIVE`, as on AWS,
where this takes about two minutes. Nothing is replicated between them. A customer-managed KMS key
is recorded and reported; nothing is encrypted with it.

## Cluster policies

`PutClusterPolicy`, and `CreateCluster` with a `policy`, check the document as DSQL does and refuse
it with DSQL's messages:

- JSON that does not parse, and a statement with no `Principal` or `Resource`, or with
  `NotPrincipal` or `NotResource`;
- a document missing or misusing an element: no `Statement`, a `Version` other than `2008-10-17`
  or `2012-10-17`, a member of no policy grammar, an `Effect` other than `Allow` or `Deny`, an
  unknown condition operator (operators are matched as written), and a principal that names no one:
  an IAM role or user is looked up in its account, and an account is always taken to exist;
- a service principal;
- an `Allow` to everyone (`"*"` or an `AWS` `"*"`) that no condition restricts. A condition
  restricts with `aws:SourceVpc`, `aws:SourceVpce`, `aws:SourceVpcArn`, `aws:SourceArn`,
  `aws:SourceAccount`, `aws:SourceOrgID`, `aws:SourceOrgPaths`, `aws:PrincipalAccount`,
  `aws:PrincipalArn`, `aws:PrincipalOrgID`, `aws:PrincipalOrgPaths`, `aws:PrincipalServiceName`,
  `aws:PrincipalServiceNamesList`, `aws:VpceAccount`, `aws:VpceOrgID`, `aws:VpceOrgPaths`,
  `aws:Ec2InstanceSourceVpc` or `aws:userid` under an equality operator, or a pattern without a
  wildcard (for an ARN, without one in the account); with an `aws:SourceIp` prefix of at least /8
  for IPv4 or /32 for IPv6;
  or with `aws:PrincipalIsAWSService` true. `IfExists`, `ForAllValues:` and negated operators never
  restrict;
- a policy that explicitly denies the caller both `dsql:PutClusterPolicy` and
  `dsql:DeleteClusterPolicy`, unless `bypassPolicyLockoutSafetyCheck` is true.

The policy is kept and returned in DSQL's form: pretty-printed, its elements in DSQL's order, a
list of one value written as that value, condition values as text, an account id as its root ARN,
and `2008-10-17` when it has no `Version`. A policy's version is the time it was set, in epoch
milliseconds. Cluster policies are not enforced: Floci's IAM enforcement does not cover DSQL.

## CloudFormation

`AWS::DSQL::Cluster` waits for `ACTIVE`, or `PENDING_SETUP` for a multi-Region cluster. `Ref`
returns the identifier; `Fn::GetAtt` supports `Identifier`, `ResourceArn`, `Status`, `CreationTime`,
`Endpoint`, `VpcEndpoint`, `VpcEndpointServiceName` and, while the cluster has a policy,
`PolicyVersion`. Properties are checked against the resource schema's types, as on AWS (a
`PolicyDocument` is text). An update leaves a property the template no longer sets as it was,
deletion protection included, but removes the tags and the policy it no longer sets; a policy the
template does not change keeps its version. A protected cluster makes the stack's deletion fail, as
on AWS.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_DSQL_ENABLED` | `true` | Enable or disable Aurora DSQL |
| `FLOCI_SERVICES_DSQL_ENDPOINT_SUFFIX` | `localhost.floci.io` | Domain the endpoints are named under, `<cluster-id>.dsql.<region>.<suffix>` |

See the [Aurora DSQL API Reference](https://docs.aws.amazon.com/aurora-dsql/latest/APIReference/Welcome.html)
and the [Aurora DSQL User Guide](https://docs.aws.amazon.com/aurora-dsql/latest/userguide/what-is-aurora-dsql.html).
