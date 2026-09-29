# EventFlow — Distributed Event-Driven Backend

[![CI](https://github.com/Mr-Wolv/EventFlow_Bakr101_2026/actions/workflows/ci.yml/badge.svg)](https://github.com/Mr-Wolv/EventFlow_Bakr101_2026/actions/workflows/ci.yml)

**Java 21 | Spring Boot | Apache Kafka | Docker | Kubernetes | GitHub Actions**

Two independently deployable Spring Boot services communicating through asynchronous
Kafka events. A deliberately small system built to demonstrate distributed-systems
fundamentals with reproducible evidence — not another CRUD app.

- **Order Service** accepts `POST /orders` and publishes an `OrderCreated` event.
- **Fulfillment Service** consumes the event, is **idempotent**, retries transient
  failures with exponential backoff, and dead-letters poison records.

No frontend. No authentication. No database. Every scope decision is documented and
deliberate (see [Scope decisions](#scope-decisions)).

## Project status — what is verified vs. documented

This project makes a point of not claiming anything it cannot show. The honest split:

| Capability | Status |
|---|---|
| Build + 19 unit tests | ✅ **Verified** — `mvn -B verify`, output in [docs/evidence.md](docs/evidence.md) |
| Docker Compose stack (Kafka 4.0 KRaft + topic init + both services) | ✅ **Verified** — all containers healthy |
| Happy path, duplicate delivery, consumer downtime, retry + recovery, dead-letter topic | ✅ **Verified** — captured terminal transcripts in [docs/evidence.md](docs/evidence.md) |
| Kubernetes on minikube + Strimzi Kafka | ✅ **Verified** — deployed to a live minikube cluster (Kubernetes v1.37) with **in-cluster Kafka via Strimzi**; in-cluster smoke test, replica scaling, pod-kill failover and consumer-group rebalance captured in [docs/evidence.md](docs/evidence.md) §6 |
| AWS EC2 deployment | 📝 **Documented only** — creating an AWS account requires a payment method, which is not available for this project. Full workflow in [docs/aws-deployment.md](docs/aws-deployment.md) |
| CI | ✅ **Verified** — green on every push: build + tests + Docker images, plus a kind job that deploys Strimzi Kafka and smoke-tests the full in-cluster flow ([Actions](https://github.com/Mr-Wolv/EventFlow_Bakr101_2026/actions)) |

"Documented" is stated as such everywhere it applies; nothing in the docs pretends a
cloud resource was provisioned.

---

## Architecture

![EventFlow architecture](architecture/architecture.png)

*The system as built and verified: both services in the `eventflow` namespace, Kafka via
Strimzi in `kafka`, the retry → dead-letter path, and the in-memory state each service
owns. Source: [`architecture/generate.py`](architecture/generate.py) — regenerate with
`python architecture/generate.py`.*

Event published to the `orders` topic (keyed by `orderId` for per-order ordering):

```json
{
  "eventId": "c7a8f3b2-...",
  "eventType": "OrderCreated",
  "orderId": "d4e5f6a7-...",
  "customerId": "550e8400-...",
  "amount": 125.50,
  "occurredAt": "2026-09-29T10:00:00Z"
}
```

---

## Quick start (Docker Compose)

Requires Docker 24+ and Docker Compose v2. Kafka runs in **KRaft mode** (no ZooKeeper).

```bash
docker compose up -d --build
docker compose ps   # wait until kafka, order-service and fulfillment-service show (healthy)
# (the kafka-init container creates the `orders` topic and exits — exit 0 is expected)

# create an order
curl -s -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId": "550e8400-e29b-41d4-a716-446655440000", "amount": 125.50}'

# watch it flow: OrderCreated → Kafka → Fulfillment
docker compose logs -f order-service fulfillment-service
# [PUBLISHED]  event ... -> orders-0@0
# [PROCESSING] order ... amount 125.50 (from orders)
# [FULFILLED]  order ...
```

### Run without Docker (local JVMs)

Requires JDK 21 and a Kafka broker on `localhost:9092` (for example the single node
from the [Apache Kafka quickstart](https://kafka.apache.org/quickstart)).

```bash
mvn -B -DskipTests package
java -jar order-service/target/order-service-1.0.0.jar
java -jar fulfillment-service/target/fulfillment-service-1.0.0.jar
```

Both default to `KAFKA_BOOTSTRAP_SERVERS=localhost:9092` and topic `orders`; override
with the `KAFKA_BOOTSTRAP_SERVERS` and `KAFKA_TOPIC` environment variables.

---

## The four demonstrations

Full transcripts: [docs/evidence.md](docs/evidence.md). Repro steps: [docs/failure-scenarios.md](docs/failure-scenarios.md).

| Scenario | Mechanism | Expected result |
|----------|-----------|-----------------|
| **Duplicate event delivery** | Same `eventId` delivered twice (manual redelivery) | `[DUPLICATE] ... skipping` — processed exactly once |
| **Consumer downtime** | Stop fulfillment, create order, restart | Kafka retains the event; processed on restart (`auto.offset.reset: earliest`) |
| **Partial failure → retry → recovery** | Arm `ONCE_PER_EVENT` fault; processing throws after receipt | Retries with backoff (1s/2s/4s), succeeds on redelivery, order fulfilled |
| **Retry exhaustion → dead-letter** | Arm `ALWAYS` fault | Record sent to `orders.DLT`, offset committed, processing continues |

The duplicate-delivery demo needs the same `eventId` twice — `POST /orders` always
creates a *new* event, so the demo produces the original event record directly to the
topic using `kafka-console-producer` (commands in the docs).

Failure injection is a first-class feature (`POST /__admin/failure`), which makes every
scenario reproducible on demand instead of "try to kill it mid-flight and hope".

---

## How it works

**Write path (Order Service).** `POST /orders` is validated (`customerId` UUID,
`amount` ≥ 0.01), the order is stored, and an `OrderCreated` event — with a fresh
`eventId` — is sent to the `orders` topic, keyed by `orderId` so all events for one
order keep their order. The producer is idempotent with `acks=all`; the HTTP response
returns immediately with `201` and does **not** wait for the broker or the consumer.
That decoupling is what makes the system eventually consistent.

**Consume path (Fulfillment Service).** The listener checks the event's `eventId`
against a dedup set (atomic check-and-record), so a redelivered event is skipped and
committed. New events pass through a failure-injection point (for the demos), then
mark the order `FULFILLED` — a safe-to-repeat state transition. A thrown exception is
retried in-process with exponential backoff (1s, 2s, 4s); records that keep failing
are published to `orders.DLT` with their original payload and failure headers, and the
partition moves on.

The full step-by-step processing contract — including what state survives a crash at
each point — is in [docs/audit-trail.md](docs/audit-trail.md), and the guarantee
analysis in [docs/distributed-systems.md](docs/distributed-systems.md).

---

## API

### Order Service (`:8080`)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/orders` | Create order → `201 Created` + `Location` header. Validated (`customerId` UUID, `amount` ≥ 0.01). |
| GET | `/orders/{id}` | Fetch order → `200` or `404`. |
| GET | `/actuator/health` | Health (readiness/liveness groups enabled for K8s probes). |

### Fulfillment Service (`:8081`)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/__admin/stats` | Unique events processed, orders fulfilled, injected failures, current fault mode. |
| GET | `/__admin/orders/{orderId}/status` | `PENDING` or `FULFILLED` for a given order. |
| POST | `/__admin/failure` | Arm a fault: `{"fault": "NONE" \| "ALWAYS" \| "ONCE_PER_EVENT"}`. |
| GET | `/__admin/failure` | Show the currently armed fault mode and injected-failure count. |
| POST | `/__admin/failure/clear` | Reset fault mode to `NONE`. |
| GET | `/actuator/health` | Health. |

**Error semantics (both services):** consistent JSON errors — `400` with a message on
bad input (invalid enum values are echoed back with the accepted list), `404` for
unknown routes or unknown order ids, `405` for a wrong HTTP method, `415` for a wrong
Content-Type, `500` only for genuine unexpected failures. Verified per-case in
[docs/evidence.md](docs/evidence.md) §7.

---

## Tests

```bash
mvn -B verify
```

19 unit tests across both modules, including: idempotency under a 16-thread duplicate
race (exactly one acceptance), duplicate delivery skipping, failure injection with
idempotency-record rollback, retry-recovery on redelivery, and API edge-case semantics
(404/405/415/400 never masquerading as 500s).

---

## Kubernetes

Manifests live in [`k8s/`](k8s/) — Namespace, ConfigMap, two Deployments (readiness +
liveness probes, resource requests/limits), two ClusterIP Services, and the Strimzi
`KafkaTopic` CR for the `orders` topic. Fulfillment runs **2 replicas** to demonstrate
consumer-group distribution and replica management. This exact setup was deployed and
verified on a live minikube cluster with in-cluster Kafka — transcripts in
[docs/evidence.md](docs/evidence.md) §6.

```bash
kubectl apply -f k8s/namespace.yaml   # first — see note in docs/kubernetes.md
kubectl apply -f k8s/
kubectl get pods -n eventflow
kubectl scale deployment fulfillment-service --replicas=3 -n eventflow
```

Details, including running Kafka in-cluster with Strimzi: [docs/kubernetes.md](docs/kubernetes.md).

## AWS

The EC2 deployment workflow (single instance, Docker Compose, security group, teardown)
is documented in [docs/aws-deployment.md](docs/aws-deployment.md). **Documented only:**
creating an AWS account requires a payment method at sign-up, which was not available
for this project — no instance was ever launched, and the docs say so explicitly.

## CI

[.github/workflows/ci.yml](.github/workflows/ci.yml) — two jobs on every push:

1. **Build, test, Docker images** — JDK 21 (Temurin) with Maven caching, `mvn -B verify`
   (19 unit tests), then `docker compose build` for both service images.
2. **Kubernetes (kind)** — builds the images, boots a kind cluster, deploys the Strimzi
   operator + single-node Kafka, applies [`k8s/`](k8s/), then smoke-tests the real
   in-cluster flow: `POST /orders` → consumed → `FULFILLED` (polls up to 60s, fails the
   job otherwise). The same evidence as [docs/evidence.md](docs/evidence.md) §6,
   re-proven on every commit.

---

## Project structure

```
EventFlow/
├── pom.xml                             # Multi-module root (Spring Boot 3.3 parent)
├── architecture/
│   ├── architecture.png                # System diagram (rendered)
│   └── generate.py                     # Diagram source — python architecture/generate.py
├── docker-compose.yml                  # KRaft Kafka + topic init + both services
├── order-service/
│   ├── Dockerfile                      # Multi-stage, dependency-layer cached
│   └── src/main/java/com/eventflow/order/
│       ├── OrderApplication.java
│       ├── OrderController.java        # POST/GET /orders, 201+Location, 404
│       ├── OrderService.java           # save → publish
│       ├── OrderRepository.java        # In-memory store (scope decision)
│       ├── EventPublisher.java         # KafkaTemplate, orderId key, delivery callback
│       ├── OrderCreatedEvent.java      # Event record (ISO-8601 timestamp)
│       ├── OrderRequest.java           # Validated request DTO
│       ├── Order.java                  # Aggregate
│       └── GlobalExceptionHandler.java # Consistent JSON errors
├── fulfillment-service/
│   ├── Dockerfile
│   └── src/main/java/com/eventflow/fulfillment/
│       ├── FulfillmentApplication.java
│       ├── FulfillmentConsumer.java    # @KafkaListener: idempotency → inject → fulfill
│       ├── IdempotentConsumer.java     # Atomic eventId dedup set
│       ├── FailureInjector.java        # NONE / ALWAYS / ONCE_PER_EVENT faults
│       ├── KafkaErrorConfig.java       # Backoff retry + DeadLetterPublishingRecoverer
│       ├── AdminController.java        # /__admin stats + fault control
│       ├── ErrorExceptionHandler.java  # Same error shape as order-service; enum errors self-document
│       ├── OrderFulfillmentStore.java  # In-memory state
│       ├── OrderStatus.java            # PENDING / FULFILLED
│       └── OrderCreatedEvent.java      # Consumer-owned event copy
├── k8s/                                # namespace, configmap, deployments, services, Strimzi KafkaTopic
├── docs/
│   ├── evidence.md                     # Captured transcripts of every demo
│   ├── distributed-systems.md          # Guarantees and tradeoffs
│   ├── failure-scenarios.md            # Step-by-step repro commands
│   ├── kubernetes.md                   # Deploy + scale guide
│   ├── aws-deployment.md               # EC2 workflow (documented only)
│   └── audit-trail.md                  # Processing contract walkthrough
└── .github/workflows/ci.yml
```

## Technologies

| Layer | Technology |
|-------|-----------|
| Language | Java 21 |
| Framework | Spring Boot 3.3 (Web, Validation, Actuator, Spring Kafka) |
| Messaging | Apache Kafka 4.x in KRaft mode — Compose runs 4.0, Strimzi ran 4.3.1; the same service images were verified against both brokers |
| Containerization | Docker (multi-stage builds) |
| Orchestration | Kubernetes (Deployments, Services, ConfigMaps, probes) |
| CI | GitHub Actions |
| Cloud (documented) | AWS EC2 workflow |

## Scope decisions

Deliberate limitations, each with a documented production upgrade path in
[docs/audit-trail.md](docs/audit-trail.md):

- **In-memory state** — no database; an Order Service restart loses orders. Production:
  PostgreSQL + JPA, or the event-sourced path.
- **Process-local idempotency** — the dedup set does not survive restarts and is not
  shared across replicas. Fulfillment itself is a safe-to-repeat state transition, so
  redelivery stays safe. Production: durable processed-events store (DB unique
  constraint / Redis) or a processed-events topic.
- **Single-partition topic** — with one partition only one consumer replica receives
  records. Production: partition the topic by `orderId`.
- **Fire-and-forget publishing** — delivery is confirmed via logged callbacks, not
  transactional with the save. Production: transactional outbox.
- **No auth, no tracing, no schema registry** — out of scope by design.
- **Per-instance observability endpoints** — `/__admin/stats` and the order-status
  endpoint read local in-memory state, so behind a load-balanced Service they may hit a
  replica that did not process a given event (observed during the K8s run: a stats query
  via Service DNS returned zero on the replica without the partition). Demo queries target
  a specific pod; production needs shared state.

The point of this repository is architecture and failure-mode reasoning, and each demo
in [docs/evidence.md](docs/evidence.md) is a captured, real terminal transcript.
