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
| `CreateCluster` | Creates a single-Region cluster, `CREATING` in the answer and `ACTIVE` right after; deletion protection is on unless turned off |
| `ListClusters` | Lists the Region's clusters, paginated |
| `GetCluster` | Returns a cluster, its endpoint and encryption details |
| `UpdateCluster` | Changes deletion protection, the KMS key and the multi-Region properties |
| `DeleteCluster` | Deletes a cluster; refused while deletion protection is on |
| `GetVpcEndpointServiceName` | Returns the Region's VPC endpoint service name |
| `PutClusterPolicy` | Sets the cluster's resource policy, with optimistic versioning |
| `GetClusterPolicy` | Returns the resource policy and its version |
| `DeleteClusterPolicy` | Removes the resource policy |
<!-- floci:actions:end -->

`TagResource`, `UntagResource` and `ListTagsForResource` work on cluster ARNs. Cluster identifiers
are 26 characters of lowercase base32, as DSQL's are, and invalid ones are refused with DSQL's
messages. A cluster's endpoint is `<cluster-id>.dsql.<region>.localhost.floci.io`.

Clusters are single-Region: multi-Region properties are stored and returned, not replicated. A
customer-managed KMS key is recorded and reported; nothing is encrypted with it.

## CloudFormation

`AWS::DSQL::Cluster` waits for `ACTIVE`. `Ref` returns the identifier; `Fn::GetAtt` supports
`Identifier`, `ResourceArn`, `Status`, `CreationTime`, `Endpoint`, `VpcEndpoint` and
`VpcEndpointServiceName`. A protected cluster makes the stack's deletion fail, as on AWS.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_DSQL_ENABLED` | `true` | Enable or disable Aurora DSQL |
| `FLOCI_SERVICES_DSQL_ENDPOINT_SUFFIX` | `localhost.floci.io` | Domain the endpoints are named under, `<cluster-id>.dsql.<region>.<suffix>` |

See the [Aurora DSQL API Reference](https://docs.aws.amazon.com/aurora-dsql/latest/APIReference/Welcome.html)
and the [Aurora DSQL User Guide](https://docs.aws.amazon.com/aurora-dsql/latest/userguide/what-is-aurora-dsql.html).
