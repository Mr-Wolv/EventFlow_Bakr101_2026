# EventFlow AWS Path (LocalStack-Validated)

A serverless/event-driven **variant** of EventFlow. The primary deployment stays
Kafka + Kubernetes; this path re-expresses the same domain flow with AWS services and
is integration-tested locally against [LocalStack](https://localstack.cloud):

```
SQS (orders queue)  ──►  Lambda (Java 21)  ──►  DynamoDB (event record, idempotent put)
                                   │
                                   └────────►  S3 (raw event archive)
```

> **Cloud validation note:** the AWS path is developed and integration-tested locally
> against LocalStack. **No production AWS account or cloud deployment is claimed** —
> the LocalStack token authenticates the emulation workspace only; it is not an AWS
> credential, and no AWS billing exists for this project.

## What it demonstrates

- **Terraform against AWS APIs** — `init`/`plan`/`apply`/`destroy` are real, executed,
  and produce real provisioned resources (locally).
- **IAM correctness under `ENFORCE_IAM=1`** — the execution role's policy is evaluated
  for real; remove a needed action and the pipeline fails.
- **Durable idempotency** — the Lambda stores each event with a conditional put
  (`attribute_not_exists(eventId)`), so SQS at-least-once redelivery is a no-op.
  This is the deliberate contrast with the Kafka path's process-local dedup set
  (see [docs/aws-architecture.md](../docs/aws-architecture.md)).
- **At-least-once end to end** — unexpected errors are rethrown so the message
  returns to the queue, mirroring the Kafka path's retry-then-DLT contract.

## Layout

```
aws/
├── lambda/                 # Java 21 Lambda (standalone Maven build)
│   ├── pom.xml             # shaded fat jar; targets the Lambda runtime baseline
│   └── src/.../OrderCreatedHandler.java
├── terraform/              # S3, DynamoDB, SQS, IAM, Lambda, event-source mapping
│   ├── providers.tf        # AWS provider pinned to the LocalStack endpoint
│   ├── main.tf             # data plane resources
│   ├── iam.tf              # execution role + least-privilege policy
│   ├── lambda.tf           # function + SQS event source mapping
│   └── outputs.tf
└── validate.sh             # automated integration test (send → assert → optionally duplicate)
```

## Run it locally

Prerequisites: Docker (LocalStack needs the Docker socket mounted — Lambda v2 runs
functions as real containers), JDK, Maven, Terraform ≥ 1.6, `awscli` + `awscli-local`
(`pip install awscli awscli-local`), and a LocalStack Hobby token in
`LOCALSTACK_AUTH_TOKEN` (kept in the gitignored `.env`; never committed).

```bash
# 1. LocalStack (from the repo root) — loopback-only port, socket for Lambda:
TOKEN=$(grep '^LOCALSTACK_AUTH_TOKEN=' .env | cut -d= -f2- | tr -d '\r')
docker run -d --name localstack-main -p 127.0.0.1:4566:4566 \
  -e LOCALSTACK_AUTH_TOKEN="$TOKEN" -e ENFORCE_IAM=1 -e LAMBDA_EXECUTOR=docker \
  -v localstack-data:/var/lib/localstack \
  -v /var/run/docker.sock:/var/run/docker.sock \
  localstack/localstack-pro:latest

# 2. Function package (a shaded jar IS a valid Lambda deployment zip):
mvn -f aws/lambda/pom.xml clean package

# 3. Provision (endpoints pinned to LocalStack by providers.tf):
cd aws/terraform
terraform init
terraform plan
terraform apply -auto-approve

# 4. Validate end to end (from the repo root):
bash aws/validate.sh                 # send → assert DynamoDB + S3
FLAG_DUPLICATE=1 bash aws/validate.sh # also prove durable idempotency

# 5. Tear everything down:
cd aws/terraform && terraform destroy -auto-approve
```

Full step-by-step with captured output: [docs/local-aws-validation.md](../docs/local-aws-validation.md).
Architecture discussion: [docs/aws-architecture.md](../docs/aws-architecture.md).
