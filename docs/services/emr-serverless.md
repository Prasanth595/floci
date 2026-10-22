# EMR Serverless

EMR Serverless is an AWS service that provides serverless data analytics environments. Floci provides an emulator for the management-plane API (REST JSON) which covers the lifecycle of an Application and of the job runs submitted to it.

## Configuration

| Key | Description | Default |
|-----|-------------|---------|
| `floci.emrserverless.enabled` | Whether the EMR Serverless API is enabled | `true` |
| `floci.emrserverless.port` | The port the service is exposed on | `4566` |
| `floci.services.emrserverless.job-run-duration-seconds` | How long a `BATCH` job run stays `RUNNING` before it succeeds; `0` makes it `SUCCESS` as soon as it starts | `0` |

## Endpoints

The emulator implements the standard AWS `emr-serverless` service endpoints:

* `POST /applications` (CreateApplication)
* `GET /applications` (ListApplications)
* `GET /applications/{applicationId}` (GetApplication)
* `PATCH /applications/{applicationId}` (UpdateApplication)
* `DELETE /applications/{applicationId}` (DeleteApplication)
* `POST /applications/{applicationId}/start` (StartApplication)
* `POST /applications/{applicationId}/stop` (StopApplication)
* `POST /applications/{applicationId}/jobruns` (StartJobRun)
* `GET /applications/{applicationId}/jobruns` (ListJobRuns)
* `GET /applications/{applicationId}/jobruns/{jobRunId}` (GetJobRun)
* `DELETE /applications/{applicationId}/jobruns/{jobRunId}` (CancelJobRun)
* `POST /tags/{resourceArn}` (TagResource)
* `DELETE /tags/{resourceArn}?tagKeys=...` (UntagResource)
* `GET /tags/{resourceArn}` (ListTagsForResource)

The tagging operations work on application ARNs, as `CreateApplication` returns them
(`arn:aws:emr-serverless:<region>:<account>:/applications/<id>`). They share the tags `CreateApplication`
stores, so `GetApplication` always reports the current set. Following the EMR Serverless user guide, a
resource holds at most 50 tags, and `aws:` in any case is reserved as the prefix of a tag key or value.

Applications, creation client tokens, and application tags are scoped to the request's AWS Region.
An application created in one Region is not returned by list, get, or tag requests signed for another.

`DeleteApplication` leaves the application in `TERMINATED` state. `GetApplication` continues to
report that state so lifecycle waiters can observe it, while an unfiltered `ListApplications`
omits the terminated application. A terminated application cannot be started or stopped again.

## Job runs

Job runs are metadata only: nothing executes. A run follows the job run states and `GetJobRun`'s
response shape, and settles when it is read.

* **Lifecycle**: a run is `RUNNING` from the moment it is submitted. With the default
  `job-run-duration-seconds` of `0`, a `BATCH` run is `SUCCESS` as soon as it starts; a positive
  duration keeps it `RUNNING` that long. A run whose duration would pass its
  `executionTimeoutMinutes` (720 when not given, as AWS reports it) is `CANCELLED` at the timeout.
  A `STREAMING` run stays `RUNNING` until it is cancelled.
* **Auto-start**: a run submitted to a `CREATED` or `STOPPED` application starts the application,
  as `autoStartConfiguration.enabled` defaults to `true`. With auto-start turned off, the run is
  refused with `ValidationException` until the application is started. A run on a terminated
  application is refused.
* **Idempotency**: `clientToken` is the idempotency token. Repeating a request with the same token
  returns the original run; reusing the token with a different request answers `ConflictException`
  (HTTP 409) "Request did not match the original".
* **CancelJobRun** moves a run that has not finished to `CANCELLED`. A finished run answers
  `ValidationException` "Job run is not in a cancellable state".
* **ListJobRuns** returns the newest runs first, filtered by `states`, `createdAtAfter`,
  `createdAtBefore` and `mode`, at most 50 per page. With several `states`, the runs are grouped by
  state in the order the filter names them. An unknown application has no runs. A `nextToken`
  resumes only the listing it came from, with the same application and filters.
* `createdBy` is the caller's ARN, as `GetCallerIdentity` reports it. `releaseLabel` and
  `networkConfiguration` come from the application.

## Limitations and Differences from AWS

* **Jobs do not execute**: `StartJobRun` records a job run and moves it through its states, but no Spark or Hive job runs.
* **Unsourced errors**: AWS documents neither the error for a run submitted to an application whose auto-start is off nor the one for a run on a terminated application; Floci answers both with `ValidationException`. A run needs a `jobDriver`, as every `JobRun` carries one, and `jobDriver` is a union, so a run without a driver, or with one that sets both `sparkSubmit` and `hive` or neither, is refused with `ValidationException` too, in Floci's own wording.
* **StopApplication with running jobs**: AWS requires running jobs to finish or be cancelled before an application stops. Floci does not enforce it; with the default duration of `0` no `BATCH` run is ever running.
* **Not emulated**: job run attempts and retries, queuing (`QUEUED`), `totalResourceUtilization` and `billedResourceUtilization`, and `shutdownGracePeriodInSeconds` (accepted and ignored).
* **Instant Start/Stop**: Floci marks the application `STARTED` or `STOPPED` immediately without provisioning actual compute capacity in the background.
* **Data Plane**: Floci does not implement the data-plane API or any execution environments.
* **Job run tags**: the tagging APIs do not accept job run ARNs (`.../jobruns/<id>`) yet; they answer
  `ResourceNotFoundException`. Tags given to `StartJobRun` are stored and returned by `GetJobRun`.
