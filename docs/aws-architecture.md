# EventFlow on AWS — Architecture Variant

EventFlow's primary deployment is **Kafka + Kubernetes**. This document describes the
**AWS path**: the same domain flow (an `OrderCreated` event reaches fulfillment)
re-expressed with managed AWS services, developed and validated locally with
LocalStack. Comparing the two paths is the point — where the guarantees match, where
they differ, and why each design makes its tradeoffs.

> **Cloud validation note:** the AWS path is developed and integration-tested locally
> against LocalStack. No production AWS account or cloud deployment is claimed.

## The two paths side by side

| Concern | Kafka + Kubernetes (primary) | AWS path (this variant) |
|---|---|---|
| Ingress | `POST /orders` → Order Service | producer sends to SQS |
| Transport | Kafka topic `orders` (KRaft broker) | SQS standard queue |
| Processing | Fulfillment Service (Spring Boot, 2 replicas) | Lambda (Java 21), event-source mapping |
| State | In-memory store (process-local) | DynamoDB table `eventflow-orders` |
| Idempotency | In-memory `ConcurrentHashMap` keyset — process-local, lost on restart | Conditional put `attribute_not_exists(eventId)` — durable, shared |
| Failure handling | `DefaultErrorHandler`: 3 retries (1s/2s/4s) → `orders.DLT`, partition continues | Message returns to queue on error → retry with redelivery; poison messages need a DLQ in production |
| Ordering | Per-order (records keyed by `orderId`) | None on a standard queue (FIFO queues exist, with throughput tradeoffs) |
| Deployment unit | Docker images → kind/minikube, Strimzi Kafka | Terraform-managed S3/DynamoDB/SQS/IAM/Lambda |
| Verified with | `mvn verify` (45 tests), CI kind job with in-cluster smoke test | `aws/validate.sh` against LocalStack (with `ENFORCE_IAM=1`) |

## Why the guarantees differ — and why that's the interesting part

**Idempotency is stronger on the AWS path — by construction.** The Kafka path's dedup
set lives inside one process: restart wipes it, replicas don't share it, and the
project documents this honestly as a scope decision. On the AWS path the dedup store
*is* the database: every invocation — cold start, concurrent, after months — checks
the same DynamoDB item, and the conditional put makes "already processed" an atomic
no-op. Same domain rule, different durability class, chosen by where the state lives.

**Ordering is weaker on the AWS path.** Kafka guarantees per-key order (records are
keyed by `orderId`); an SQS *standard* queue guarantees only at-least-once, with
possible reordering. For this domain — one state transition per event, idempotent
apply — that is acceptable, and the tradeoff is explicit. If strict per-order
processing were required, the AWS-path answer would be a FIFO queue or
EventBridge-pipe-style keying, each with throughput costs.

**Failure semantics are shaped by the transport.** Kafka: the consumer owns retry
policy in-process (exponential backoff, then DLT, then keep going — the partition is
never blocked). SQS: an exception puts the message back; the queue itself retries by
redelivering. The demonstrated behavior (a failing message does not stop other
messages) matches, but the mechanism — broker log vs. visibility timeout — is
completely different, and production AWS wiring would add a dead-letter queue with
`maxReceiveCount`, the analogue of `orders.DLT`.

**Operational surface shrinks on the AWS path.** No brokers, no consumer-group
rebalancing, no K8s manifests for the services — the "cluster" is AWS's problem.
What appears instead is IaC: the entire data plane is described in ~150 lines of
Terraform and is reproducible with `apply`/`destroy`.

## Mapping table

| EventFlow concept | Kafka/K8s path | AWS path |
|---|---|---|
| "the orders topic" | Kafka topic `orders` (Strimzi `KafkaTopic` CR) | SQS `eventflow-orders-queue` |
| "the consumer" | `FulfillmentConsumer` `@KafkaListener` | `OrderCreatedHandler` + SQS event-source mapping |
| "dedup check" | `IdempotentConsumer.tryProcess` (in-memory) | DynamoDB conditional put (`attribute_not_exists`) |
| "mark fulfilled" | `OrderFulfillmentStore.markFulfilled` (in-memory) | item attribute `status = FULFILLED_BY_LAMBDA` |
| "dead-letter topic" | `orders.DLT` via `DeadLetterPublishingRecoverer` | (production: SQS DLQ via `maxReceiveCount` — documented, not deployed) |
| "the demo archive" | log lines (`[FULFILLED] ...`) | S3 `s3://eventflow-order-archive/orders/<orderId>/<eventId>.json` |

## What is deliberately NOT claimed

- No production AWS account exists; nothing here has ever been deployed to real AWS.
- The LocalStack token authenticates the local emulation workspace; it is not an AWS
  credential and grants no cloud access.
- No SQS DLQ, no CloudWatch alarms, no X-Ray tracing are deployed — the variant
  demonstrates the event path and idempotency contract, not a production AWS landing
  zone.

The boundary sentence to remember: **"Cloud validation note: the AWS deployment path
is developed and integration-tested locally against LocalStack. No production AWS
account or cloud deployment is claimed."** It appears in [aws/README.md](../aws/README.md)
and the root README status table.
