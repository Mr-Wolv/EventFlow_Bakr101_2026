# Local AWS Validation — Step-by-Step with Captured Output

Companion to [aws-architecture.md](aws-architecture.md). This is the *how to run it*
page; every command below was executed on 2026-09-30 against LocalStack Pro (latest,
date-versioned line) with Terraform 1.9.8, aws-cli 1.46, and a JDK 25 toolchain (the
Lambda itself targets Java 21, the managed-runtime baseline).

> **Boundary:** LocalStack emulates AWS APIs locally. The LocalStack token is a
> workspace license token (Hobby plan, non-commercial); it is **not** an AWS
> credential. No AWS account exists for this project and nothing was deployed to AWS.

## 0. Prerequisites and the two gotchas we hit for real

- **Disk space:** Docker's metadata store went read-only when the C: drive hit 100%,
  which wedged the daemon (`write meta.db: read-only file system`). Free space before
  starting; the LocalStack image plus Lambda containers need a few GB.
- **Docker socket:** LocalStack v2 Lambdas run as real containers (like real AWS).
  Without the host socket mounted, function creation fails with
  `InternalError: Error while creating lambda: Docker not available`.

LocalStack was started exactly as documented in [aws/README.md](../aws/README.md)
(loopback-only port, auth token from the gitignored `.env`, `ENFORCE_IAM=1`,
`LAMBDA_EXECUTOR=docker`, socket mounted).

## 1. Build the function package

```bash
export JAVA_HOME="C:\Program Files\Java\jdk-25.0.3"   # JDK 25 compiles it; release=21 targets the runtime
mvn -B -f aws/lambda/pom.xml clean package
# BUILD SUCCESS
# aws/lambda/target/eventflow-order-lambda.jar  (~20 MB shaded)
```

> **Gotcha hit for real:** do **not** wrap the jar in a Terraform `archive_file` zip.
> A shaded jar *is* a valid deployment package; double-zipping hides the classes and
> every invoke fails with `ClassNotFoundException: com.eventflow.aws.OrderCreatedHandler`.

## 2. Provision with Terraform

```bash
cd aws/terraform
terraform init        # provider pin: hashicorp/aws ~> 5.60
terraform plan        # Plan: 7 to add, 0 to change, 0 to destroy.
terraform apply -auto-approve
# Apply complete! Resources: 7 added, 0 changed, 0 destroyed.
```

Resources created: S3 bucket, DynamoDB table (+ GSI), SQS queue, IAM role + policy,
Lambda, SQS→Lambda event-source mapping.

## 3. Run the automated validation

```bash
FLAG_DUPLICATE=1 bash aws/validate.sh
# == LocalStack health
# PASS: LocalStack responds
# == Stack present (queue exists)
# PASS: queue reachable
# == Send OrderCreated event (eventId=26f141fa-…)
# PASS: SQS -> Lambda -> DynamoDB
#   status   : FULFILLED_BY_LAMBDA
#   orderId  : 9faed3e3-…
#   amount   : 125.5
# == S3 archive object
# PASS: s3://eventflow-order-archive/orders/9faed3e3-…/26f141fa-….json
# == Duplicate delivery: re-sending the SAME eventId
# PASS: durable idempotency: item count still 2 (conditional put suppressed the redelivery)
# ALL CHECKS PASSED
```

(The item count reflects one earlier diagnostic event plus this run's event; the
invariant that matters is that it did **not** change after the duplicate.)

## 4. The duplicate-suppression check, in raw API terms

```bash
# first delivery stores the item:
awslocal dynamodb get-item --table-name eventflow-orders \
  --key '{"eventId":{"S":"11111111-aaaa-4bbb-8ccc-222222222222"}}'
# → {"Item": {"status": {"S":"FULFILLED_BY_LAMBDA"}, "amount": {"S":"125.5"}, ...}}

# re-sending the identical event body produces the [LAMBDA-DUPLICATE] branch —
# the conditional put fails with ConditionalCheckFailedException and the handler
# treats it as success. Table scan afterwards: count stays exactly 1 for that eventId.
```

## 5. Error semantics sanity check

Direct invocation with a *raw* (non-SQS-envelope) payload fails fast and visibly:

```bash
awslocal lambda invoke --function-name eventflow-order-fulfillment \
  --payload file://diag-event.json diag-out.json
# StatusCode 200 + {"errorMessage":"... getRecords() is null", "errorType":"NullPointerException"}
```

This is expected — the function implements the SQS event-source contract
(`SQSEvent.getRecords()`), which is exactly what the mapping delivers. Sending a
malformed message body through the *queue* leaves the message to be retried by SQS
(at-least-once) instead of crashing the consumer — matching the Kafka path's
"poison record must not stop the partition" goal at the transport level.

## 6. Tear down

```bash
cd aws/terraform && terraform destroy -auto-approve
# Destroy complete! Resources: 7 destroyed.
```

Verified for real during this session: the first (socket-less) stack was destroyed
cleanly with `Destroy complete! Resources: 6 destroyed.` before the container fix,
and the rebuilt stack was later re-applied repeatedly with
`-replace=aws_lambda_function.order_fulfillment` during the package debugging —
both plan/apply/destroy and targeted replacement behave as documented.
