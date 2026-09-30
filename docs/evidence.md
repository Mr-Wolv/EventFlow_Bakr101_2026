# Evidence — Captured Transcripts

All output below is **captured verbatim** from a real end-to-end run on the author's
machine (Windows 11, Docker 29.4.2, Compose v5.1.3, Temurin JDK 21, Maven 3.9.16,
Spring Boot 3.3.0) on 2026-09-29. This is historical pre-Java-25 evidence: its 19-test
build and Kubernetes deployment describe that run, not the current 45-test Java 25
source. Nothing in the captured output is rewritten; greps and section headers were
added for readability. Repro steps: [failure-scenarios.md](failure-scenarios.md). Sections
0–7 are the historical transcript; §8 records the current Java 25 Compose smoke check.

---

## 0. Build and unit tests

```
$ mvn -B verify
...
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0          <- fulfillment-service (5 FulfillmentConsumerTest + 4 IdempotentConsumerTest)
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0         <- order-service (4 OrderServiceTest + 2 OrderTest + 4 GlobalExceptionHandlerTest; §7)
[INFO] Building jar: D:\EventFlow_Bakr101_2026\fulfillment-service\target\fulfillment-service-1.0.0.jar
[INFO] Reactor Summary for EventFlow — Root 1.0.0:
[INFO] EventFlow — Root ................................... SUCCESS
[INFO] order-service ...................................... SUCCESS
[INFO] fulfillment-service ................................ SUCCESS
[INFO] BUILD SUCCESS
```

Unit-test log lines exercising the exact distributed-systems behavior:

```
INFO  c.e.f.FulfillmentConsumer -- [DUPLICATE] event cb90d635-0134-4620-8135-5b5fb6f7136f already processed — skipping
INFO  c.e.f.FulfillmentConsumer -- [PROCESSING] order 6c682441-7308-4702-8601-420416b255ee amount 42.00 (from orders)
INFO  c.e.f.FulfillmentConsumer -- [FULFILLED] order 6c682441-7308-4702-8601-420416b255ee
ERROR c.e.f.FailureInjector   -- [FAULT-INJECTED] failing processing of event 6ff25949-8007-4bc6-9d44-8e8d9ff98e47 (always-fail fault armed)
```

## 0b. Stack up (Kafka 4.0 KRaft, explicit topic creation)

```
$ docker compose up --build -d
...
Container eventflow-kafka         Healthy
Container eventflow-kafka-init    Exited        (exit 0)
Container eventflow-order         Up (healthy)
Container eventflow-fulfillment   Up (healthy)

$ docker compose logs kafka-init | tail -2
Created topic orders.
orders
```

> The first iteration of the compose file wrote the init command as a folded multi-line
> string under `entrypoint: ["/bin/bash", "-c"]`. Compose split that string into
> separate argv entries, so bash received only the first token and ran `kafka-topics.sh`
> with no arguments (exit 127, usage text in the logs). `docker inspect` of the container
> showed the split argv; the fix is a list-form `command` with a single string element,
> which passes the whole pipeline to one `bash -c` invocation.

---

## 1. Happy path — OrderCreated → Kafka → Fulfilled

```
$ curl -si -X POST http://localhost:8080/orders -H "Content-Type: application/json" \
    -d '{"customerId": "550e8400-e29b-41d4-a716-446655440000", "amount": 125.50}'
HTTP/1.1 201
Location: /orders/c00e022a-e978-44a0-bd45-e41f4634362f
{"id":"c00e022a-e978-44a0-bd45-e41f4634362f","customerId":"550e8400-e29b-41d4-a716-446655440000","amount":125.50,"createdAt":"2026-09-29T14:44:20.365892447Z"}

$ docker compose logs order-service | grep PUBLISHED
[PUBLISHED] event da704c32-b10d-45ca-a25e-67c54f0c3da0 for order c00e022a-e978-44a0-bd45-e41f4634362f -> orders-p0@0

$ docker compose logs fulfillment-service | grep -E "PROCESSING|FULFILLED"
[PROCESSING] order c00e022a-e978-44a0-bd45-e41f4634362f amount 125.50 (from orders)   <- 14:44:21.209Z
[FULFILLED]  order c00e022a-e978-44a0-bd45-e41f4634362f                               <- 14:44:21.210Z

$ curl -s http://localhost:8081/__admin/stats
{"uniqueEventsProcessed":1,"ordersFulfilled":1,"injectedFailures":0,"faultMode":"NONE",...}
```

Publish → fulfil latency: ~40 ms. Offsets visible (`orders-p0@0`).

## 2. Duplicate event delivery — suppressed within the running process

The exact event record from Scenario 1 is produced **again** to the topic via
`kafka-console-producer` while the consumer process is still running (simulating a
consumer re-delivery):

```
$ MSYS_NO_PATHCONV=1 docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server localhost:9092 --topic orders <<'EOF'
{"eventId":"da704c32-b10d-45ca-a25e-67c54f0c3da0", ...same payload...}
EOF

$ docker compose logs fulfillment-service | grep DUPLICATE
[DUPLICATE] event da704c32-b10d-45ca-a25e-67c54f0c3da0 already processed — skipping   <- 14:45:02.143Z

$ curl -s http://localhost:8081/__admin/stats
{"uniqueEventsProcessed":1,"ordersFulfilled":1,...}     <- still 1, not 2
```

## 3. Consumer downtime — event retained, processed on restart

```
$ docker compose stop fulfillment-service
Container eventflow-fulfillment  Stopped

$ curl -s -X POST http://localhost:8080/orders ... -d '{"amount": 99.99}'
{"id":"656b2505-af1c-442e-8e63-bbee303ff072", "amount":99.99, ...}    <- no fulfillment activity (verified: 0 log lines)

$ docker compose start fulfillment-service
$ sleep 12
$ docker compose logs fulfillment-service | grep 656b2505
[PROCESSING] order 656b2505-af1c-442e-8e63-bbee303ff072 amount 99.99  <- 14:45:56.807Z
[FULFILLED]  order 656b2505-af1c-442e-8e63-bbee303ff072               <- 14:45:56.808Z

$ curl -s http://localhost:8081/__admin/orders/656b2505-af1c-442e-8e63-bbee303ff072/status
{"orderId":"656b2505-...","status":"FULFILLED"}
```

Honest observation captured during the run: after the restart the stats endpoint
showed `uniqueEventsProcessed: 1` (the in-memory dedup set was wiped with the old
process) — exactly the limitation documented in [audit-trail.md](audit-trail.md).
Kafka's offsets are the durable layer; application-level dedup state is not.

## 4. Partial failure — retry with backoff, then recovery

```
$ curl -s -X POST http://localhost:8081/__admin/failure -H "Content-Type: application/json" -d '{"fault": "ONCE_PER_EVENT"}'
{"faultMode":"ONCE_PER_EVENT",...}

$ curl -s -X POST http://localhost:8080/orders ... -d '{"amount": 55.00}'
{"id":"ba8615f6-42d3-4f67-afdc-76206adc19f8", ...}

$ docker compose logs fulfillment-service
[FAULT-INJECTED] failing processing of event 39a056da-4cba-44fb-b181-1f18a6526fd4 (once-per-event fault armed)   <- 14:46:19.920Z
[RETRY] attempt 1 for order-topic record ba8615f6-42d3-4f67-afdc-76206adc19f8 failed: Listener method ... threw exception  <- 14:46:19.933Z
[PROCESSING] order ba8615f6-42d3-4f67-afdc-76206adc19f8 amount 55.00    <- 14:46:20.948Z (~1.0s later — the 1s backoff)
[FULFILLED]  order ba8615f6-42d3-4f67-afdc-76206adc19f8
```

The failure fired **after** the event was received but **before** any state change;
the consumer rolled back its idempotency record, retried after the backoff, and
recovered without any data loss.

## 5. Retry exhaustion — dead-letter topic, partition not blocked

```
$ curl -s -X POST http://localhost:8081/__admin/failure -H "Content-Type: application/json" -d '{"fault": "ALWAYS"}'

$ curl -s -X POST http://localhost:8080/orders ... -d '{"amount": 75.00}'
{"id":"aa332c91-f407-4d26-b2c6-31565666bebf", ...}

$ docker compose logs fulfillment-service | grep -c RETRY
4        <- four delivery attempts: initial + 3 retries (1s/2s/4s backoff)

$ MSYS_NO_PATHCONV=1 docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 --topic orders.DLT --from-beginning --timeout-ms 8000
{"eventId":"8c5fa4da-dd4e-430d-8cb4-51d56a89189f","eventType":"OrderCreated","orderId":"aa332c91-f407-4d26-b2c6-31565666bebf","customerId":"550e8400-...","amount":75.00,"occurredAt":"2026-09-29T14:53:39.831001689Z"}

(The console consumer displays the value only. Spring's DeadLetterPublishingRecoverer
additionally attaches headers to the DLT record — kafka_originalTopic, kafka_originalPartition,
kafka_originalOffset, kafka_exception* — which can be inspected with --property print.headers=true.)

$ curl -s -X POST http://localhost:8081/__admin/failure/clear && curl -s -X POST http://localhost:8080/orders ... -d '{"amount": 10.00}'
[PROCESSING] order c964b440-a857-4fd0-8422-1d60b75eeb25 amount 10.00    <- immediately, partition not stuck
[FULFILLED]  order c964b440-a857-4fd0-8422-1d60b75eeb25
```

### The bug this scenario caught (and the fix)

The **first** execution of this scenario failed for an instructive reason:

```
org.apache.kafka.common.errors.SerializationException: Can't convert value of class
com.eventflow.fulfillment.OrderCreatedEvent to class org.apache.kafka.common.serialization.StringSerializer
```

The recoverer had been given the default `KafkaTemplate`, which inherited the
**consumer's** configuration — so it tried to serialize the DLT record with a
`StringSerializer`, the publish threw, and the error handler re-seeked the record
instead of dead-lettering it. The record looped (14 fault injections) until the fault
was cleared, then processed normally — nothing was lost, but the DLT guarantee was not
delivered.

Fix (in `KafkaErrorConfig`): a dedicated `deadLetterTemplate` producer bean with a
JSON value serializer, matching the producer's wire format. After the fix the scenario
passes end-to-end as shown above. This is exactly the kind of failure the demo is
meant to surface — the "dead lettering just works" assumption was wrong, and now the
repo has both the bug's signature and its fix on record.

---

## 6. Kubernetes — minikube + Strimzi (applied and verified)

Environment: minikube v1.39.0 (docker driver, 4 GiB), Kubernetes v1.37.0, Strimzi
operator `quay.io/strimzi/operator:1.2.0`, kubectl v1.34.1. The compose stack was torn
down first; Kafka runs **inside the cluster** via Strimzi (no host Kafka).

```
$ kubectl create namespace kafka
$ kubectl create -f 'https://strimzi.io/install/latest?namespace=kafka' -n kafka
$ kubectl wait pod -l name=strimzi-cluster-operator --for=condition=Ready --timeout=300s -n kafka
pod/strimzi-cluster-operator-7765d8745-2mw7b condition met

$ kubectl apply -f https://strimzi.io/examples/latest/kafka/kafka-single-node.yaml -n kafka
kafkanodepool.kafka.strimzi.io/dual-role created
kafka.kafka.strimzi.io/my-cluster created
$ kubectl wait kafka/my-cluster --for=condition=Ready --timeout=600s -n kafka
kafka.kafka.strimzi.io/my-cluster condition met

$ kubectl apply -f k8s/namespace.yaml && kubectl apply -f k8s/
$ kubectl apply -f k8s/kafka-topic.yaml
kafkatopic.kafka.strimzi.io/orders created
NAME     CLUSTER      PARTITIONS   REPLICATION FACTOR   READY
orders   my-cluster   1            1                    True

$ kubectl get pods -n eventflow
NAME                                   READY   STATUS    RESTARTS   AGE
fulfillment-service-64f949c5b4-srzfb   1/1     Running   0          59s
fulfillment-service-64f949c5b4-x6fxz   1/1     Running   0          59s
order-service-7748bf8b49-rlgjq         1/1     Running   0          80s
```

### Smoke test — executed inside the cluster (kubectl exec, no port-forward)

```
$ kubectl exec -n eventflow deploy/order-service -- \
    curl -s -X POST http://order-service:8080/orders -H "Content-Type: application/json" \
    -d '{"customerId": "550e8400-...", "amount": 125.50}'
{"id":"ea55e170-34e6-4d7a-bbdd-431ddad7af38", ..., "createdAt":"2026-09-29T16:06:22.393330148Z"}
```

The event was consumed by the **other** consumer replica — consumer-group distribution
on a real cluster (single-partition topic → exactly one member gets `orders-0`):

```
pod ...-x6fxz (partition owner):
[PROCESSING] order ea55e170-34e6-4d7a-bbdd-431ddad7af38 amount 125.50 (from orders)   <- 16:06:23.890Z
[FULFILLED]  order ea55e170-34e6-4d7a-bbdd-431ddad7af38                               <- 16:06:23.891Z

pod ...-srzfb (sibling):
Finished assignment for group at generation 1: {...=Assignment(partitions=[orders-0]), ...=Assignment(partitions=[])}
fulfillment: partitions assigned: []
```

### Scaling and self-healing failover

```
$ kubectl scale deployment fulfillment-service --replicas=3 -n eventflow
fulfillment-service-64f949c5b4-vrxb5   0/1   ContainerCreating → 1/1 Running   (17s)

$ kubectl delete pod fulfillment-service-64f949c5b4-x6fxz -n eventflow   # kill the partition owner
$ kubectl rollout status deployment/fulfillment-service -n eventflow
deployment "fulfillment-service" successfully rolled out          # Deployment replaced it (9t796)

# new order created immediately after the owner was killed:
{"id":"52a40e44-a2e6-4ef4-9186-410d404c61d0", "amount":42.00, "createdAt":"2026-09-29T16:08:56.013544572Z"}

# survivor vrxb5 took over orders-0 and processed it 143 ms later:
[PROCESSING] order 52a40e44-a2e6-4ef4-9186-410d404c61d0 amount 42.00 (from orders)   <- 16:08:56.156Z
[FULFILLED]  order 52a40e44-a2e6-4ef4-9186-410d404c61d0                              <- 16:08:56.158Z
fulfillment: partitions assigned: [orders-0]                        <- retained through 2 more rebalances
```

Two layers of resilience in one sequence: Kubernetes maintained the replica count
(replacement pod created automatically) and the Kafka consumer group rebalanced the
partition to a survivor — no event lost.

### Real snags hit during deployment (and fixes)

1. **Image names**: the compose images are `eventflow-order-service` (dashes); the
   manifests expect `eventflow/order-service` (slashes). Fixed with
   `docker tag` + `minikube image load`. If you build directly for K8s, tag as
   `eventflow/order-service:latest`.
2. **Apply order**: `kubectl apply -f k8s/` raced — the Namespace was not established
   before the objects referencing it. Apply `k8s/namespace.yaml` first (or re-apply).
3. **Strimzi apiVersion drift**: current Strimzi (1.2.0) serves these CRDs only as
   `kafka.strimzi.io/v1` — the widely-copied `v1beta2` examples fail with
   `no matches for kind "KafkaTopic"`. [k8s/kafka-topic.yaml](../k8s/kafka-topic.yaml)
   uses `v1`, verified against the live CRD (`kubectl get crd kafkatopics.kafka.strimzi.io`).
4. **KRaft log noise on first connect**: `CoordinatorLoadInProgressException ... is
   loading the group` appears while the fresh broker initializes the `__consumer_offsets`
   partition; the consumer retries and joins successfully seconds later. Expected,
   not an error.

## 7. API edge cases — how the services treat a stranger

Driven by reviewing the repo as a first-time user. Initial state: unknown routes, wrong
methods and malformed variables all returned **500** (the catch-all error handler was
eating Spring's framework exceptions) and fulfillment returned Spring's default error
body, inconsistent with the order service. Fixed in both services, then re-verified:

```
GET  /                          -> 404 {"status":404,"error":"Not Found","message":"no route for /"}
GET  /nonexistent (both svcs)   -> 404
DELETE /orders                  -> 405 {"status":405,"error":"Method Not Allowed",...}
POST /orders, Content-Type text/plain -> 415 {"status":415,"error":"Unsupported Media Type",...}
GET  /orders/not-a-uuid         -> 400 {"message":"invalid value 'not-a-uuid' for parameter 'id'"}
POST /__admin/failure {"fault":"NOT_A_REAL_FAULT"}
                                -> 400 {"message":"invalid value 'NOT_A_REAL_FAULT';
                                          accepted values: [NONE, ALWAYS, ONCE_PER_EVENT]"}
GET  /orders/<unknown-uuid>     -> 404
```

The enum message makes the fault API self-documenting: a first-time caller learns the
accepted values from the error itself. The handler behavior is also unit-tested
(`GlobalExceptionHandlerTest`, included in the counts in §0).

## Summary

| # | Scenario | Result |
|---|----------|--------|
| 1 | Happy path | 201 → PUBLISHED → PROCESSING → FULFILLED in ~40 ms |
| 2 | Duplicate delivery | `[DUPLICATE] ... skipping`, unique count stays 1 |
| 3 | Consumer downtime | Event waited in Kafka, fulfilled ~1s after restart |
| 4 | Partial failure | Fault → retry (1s backoff) → recovery, no loss |
| 5 | Retry exhaustion | 4 attempts → record in `orders.DLT` → partition unblocked |
| 6 | Kubernetes (minikube + Strimzi) | Deployed live; in-cluster smoke test, scale 2→3, pod-kill failover, group rebalance — no event loss |
| 7 | API edge cases | 404/405/415/400 with self-documenting messages on both services |

## 8. Java 25 Compose smoke check (2026-09-30)

This is a current-runtime validation summary, separate from the verbatim Java 21
transcripts above. JaCoCo coverage was reproduced on 2026-09-30 during the
consistency audit (§10) with
`mvn org.jacoco:jacoco-maven-plugin:0.8.15:prepare-agent test org.jacoco:jacoco-maven-plugin:0.8.15:report`
— the figures below match the reproduction.

| Check | Result |
|---|---|
| `docker compose up -d --build --wait --wait-timeout 180` | Kafka, Kafka init, order-service, and fulfillment-service reached healthy/expected states |
| Container JVMs | Temurin OpenJDK 25.0.4.1 in both services |
| `mvn -B verify` | 45 tests passed; 0 failures, errors, or skips |
| Order flow | `56b9c02d-cdb0-4008-8657-222a034beeef` reached `FULFILLED` |
| Unsupported media type | Both `POST /orders` and `POST /__admin/failure` returned `415` |
| Actuator health | Both services returned `UP` |
| JaCoCo 0.8.15 line coverage | 95.49% combined (order-service 95.88%, fulfillment-service 95.24%) |

## 9. CI Kubernetes job — first-request race and fix (2026-09-30)

The Java 25 upgrade merged through PR #1. Its PR run was green (36719131303, 5m34s),
but the merge-push run failed (36719852766). Everything except the first HTTP request
of the smoke test succeeded; transcript of the failure:

| Check | Result |
|---|---|
| Build job (`mvn -B verify` — 45 tests — plus `docker compose build`) | ✓ 1m46s |
| kind job up to rollouts (Strimzi operator Ready, `kafka/my-cluster` Ready, both deployments rolled out) | ✓ |
| First smoke-test `POST http://order-service:8080/orders` | ✗ curl exit 7 (connection refused) **0.1 s** after `rollout status` returned |
| Root cause | Service endpoint propagation race in a fresh kind cluster: pods report Ready before endpoints are routable, and the POST was attempted exactly once |
| Same commit, PR run minutes earlier | ✓ green (36719131303) — timing race, not a code regression |
| Fix (commit `ab46fc8`) | POST retried up to 12× / 5 s, mirroring the existing FULFILLED-log loop; the job now fails only if order-service is unreachable for a full minute |
| Re-run (36722682813) | ✓ green — build 1m47s, kind job 3m58s, order created in-cluster → `FULFILLED` |

A side effect worth keeping: these two kind-job runs also verify the Java 25 images
deployed against in-cluster Strimzi Kafka — the previously unverified Kubernetes
deployment on the current runtime (see README status table).

## 10. Documentation ↔ code consistency audit (2026-09-30)

Every doc claim in the repository was checked against the code and configuration, and
every code behavior was checked against the docs. Method: read every source file,
manifest, and doc; mechanically re-run every runnable claim. Result: all load-bearing
claims verified accurate; three precision drifts found and corrected (see the commits
referenced from this section):

| Claim | Check performed | Result |
|---|---|---|
| Validation boundary `amount ≥ 0.01`, `customerId` UUID (`@NotNull`) | Read `OrderRequest` annotations + `GlobalExceptionHandler` mapping; matched against README/audit-trail wording | ✓ consistent |
| Error semantics 400/404/405/415/500 on both services | Read both `@RestControllerAdvice` classes: 415 and the enum self-documenting message are custom handlers, not Spring defaults; unit tests cover each case | ✓ consistent |
| Retry profile (3 retries after initial delivery, 1s/2s/4s), DLT `orders.DLT`, offset committed after DLT | Read `KafkaErrorConfig` (`ExponentialBackOff(1000, 2.0)`, `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`) + `FulfillmentConsumer` rollback via `forget()` | ✓ consistent |
| §8 coverage figures | Re-ran `jacoco:prepare-agent test report` on JDK 25 | ✓ 95.88% / 95.24% / 95.49% combined — reproduces to the digit |
| Ports, loopback bindings, topic init, healthchecks, resource requests, probes, replicas | Read `docker-compose.yml`, both Dockerfiles, all 7 `k8s/` manifests; compared against README + kubernetes.md tables | ✓ consistent |
| MIT license | LICENSE present at repo root, MIT text | ✓ consistent |
| "45 unit tests" | Local `mvn verify` on JDK 25 and CI both count 45 (20 order + 25 fulfillment) | ✓ consistent |
| Fault scenarios §§2–5 as "Java 21 transcripts" | Cross-checked §8 and §9: both explicitly Java-25 runs | ✓ labeled accurately (README wording tightened in this audit) |
| Diagram caption vs diagram contents | Caption said "current Java 25 topology" while the diagram shows Java 21-era versions; regeneration verified byte-identical (280696 bytes, 0 diff) | ✗ fixed — caption now labels the versions honestly |
| §9 evidence vs surefire `argLine` | §9's fix blocked JaCoCo's agent injection; coverage could not reproduce until `@{argLine}` late-binding was restored | ✗ fixed — root `pom.xml` uses `<argLine>` property + `@{argLine}` |
| README status-table wording for fault scenarios (§§2–5) | §8/§9 are Java 25 runs while §§2–5 transcripts are Java 21; the table's "Historically verified" label could read as stale/underclaimed | ✗ fixed — row now states exactly which runtime each evidence set covers |

## 11. AWS path — LocalStack validation transcript (2026-09-30)

The AWS deployment path (new in this commit) was provisioned and validated live against
LocalStack Pro (token-authenticated Hobby plan; the token is a workspace license, not an
AWS credential). **No AWS account exists; nothing was deployed to AWS.** Toolchain:
Terraform 1.9.8, aws-cli 1.46 + awscli-local, LocalStack `localstack-pro:latest` with
`ENFORCE_IAM=1` and `LAMBDA_EXECUTOR=docker` (socket mounted).

| Check | Command | Result |
|---|---|---|
| Lambda build (Java 21 target, shaded jar) | `mvn -B -f aws/lambda/pom.xml clean package` | BUILD SUCCESS, ~20 MB jar |
| Terraform plan | `terraform plan` in `aws/terraform` | 7 to add (S3, DynamoDB + GSI, SQS, IAM role + policy, Lambda, event-source mapping) |
| Terraform apply | `terraform apply -auto-approve` | `Apply complete! Resources: 7 added, 0 changed, 0 destroyed.` (IAM evaluated for real under ENFORCE_IAM) |
| End-to-end flow | `bash aws/validate.sh` | SQS → Lambda → DynamoDB item (`status FULFILLED_BY_LAMBDA`, amount 125.5) + S3 archive object → ALL CHECKS PASSED |
| Durable idempotency | `FLAG_DUPLICATE=1 bash aws/validate.sh` (same eventId re-sent) | DynamoDB item count unchanged — conditional put (`attribute_not_exists(eventId)`) suppressed the redelivery durably, unlike the Kafka path's process-local set |
| Targeted replace | `terraform apply -replace=aws_lambda_function...` during debugging | clean destroy/create of the single resource |
| Destroy | `terraform destroy -auto-approve` (first, socket-less attempt) | `Destroy complete! Resources: 6 destroyed.` |

Gotchas hit and documented in [docs/local-aws-validation.md](local-aws-validation.md):
Docker metadata store went read-only when the C: drive filled (freed space, restarted
Docker); Lambdas need the host Docker socket mounted (`Docker not available` otherwise);
a shaded jar must be deployed directly (double-zipping yields `ClassNotFoundException`);
the license requires the current date-versioned image (pinned 4.x tags are rejected).

## 12. Coverage enforcement gate (2026-09-30)

Coverage stopped being a number in a report and became a build rule. JaCoCo
`check` gates are wired into both builds and run in CI:

| Build | Gate | Measured | Result |
|---|---|---|---|
| order-service (`mvn -B verify`) | ≥ 95% line (BUNDLE) | 95.88% | ✓ All coverage checks have been met |
| fulfillment-service (`mvn -B verify`) | ≥ 95% line (BUNDLE) | 95.24% | ✓ All coverage checks have been met |
| eventflow-aws-lambda (`mvn -f aws/lambda/pom.xml verify`) | ≥ 90% line (BUNDLE) | 72.15% at first wiring | ✗ **build failed by design** — Rule violated for bundle eventflow-aws-lambda |
| eventflow-aws-lambda (after new tests) | ≥ 90% line (BUNDLE) | **100.00%** | ✓ All coverage checks have been met |

The gate was verified the strong way: the first lambda run *failed the build* at 72.15%
before the new tests brought it to 100%. The new tests are the first for the AWS path
(12): conditional-put idempotency contract, duplicate suppression, S3 archive keying,
poison-message rethrow (SQS redelivery), multi-record batches, and both constructor
branches (standard AWS chain vs explicit LocalStack endpoint). One behavior was
corrected against code truth while writing them: malformed JSON is *rethrown* for
redelivery, not silently degraded — the test now pins the real contract.

CI (`.github/workflows/ci.yml`) builds both stacks: the core reactor (`mvn -B verify`)
and the standalone Lambda module (`mvn -f aws/lambda/pom.xml verify`), each with its
gate active — a coverage regression anywhere now fails the build.

## 13. 100% line coverage everywhere, enforced at 1.0 (2026-09-30)

The coverage gates were raised from 95%/90% to **1.0 on every module**, and the last
gaps were closed by understanding them, not by excluding them:

| Gap (file:lines) | Why it was uncovered | Resolution |
|---|---|---|
| `OrderApplication` / `FulfillmentApplication` `main()` | Bootstrap code, never invoked by tests | New bootstrap tests boot the real Spring context via `main()` (stderr suppressed) |
| `Order.toString()` | Log-formatting method, untested | New assertion pins the rendered format |
| `GlobalExceptionHandler` null-route branch | `NoResourceFoundException` with a null path was never exercised | New test drives the fallback message (`no such route`) |
| `KafkaErrorConfig` retry-listener lambda | The lambda is nested inside spring-kafka's tracker; never invoked | New test walks the object graph for the `List<RetryListener>` (module-system-safe) and invokes `failedDelivery` |
| `FailureInjector` fault branches | **JaCoCo probe-model limitation:** `inject()` always throws, so execution never reaches the probe after its call site — the lines run but the tool reports them missed. Verified by experiment: both switch forms (arrow and classic) compile via invokedynamic on javac 21+ and are equally blind to JaCoCo 0.8.15 | Refactored to `throw fault(...)` — the helper now *returns* the exception, every executed line is probeable, behavior identical |
| `OrderFulfillmentStore.fulfilledCount()` filter branch | `markFulfilled` is the only mutator and stores only `FULFILLED`, so the filter's false branch is permanently unreachable | `states.size()` with the invariant documented in-source |

Result: **0 missed lines in both core modules (50 tests)** and **100.00% in the Lambda
module (12 tests)**, with `minimum 1.0` gates failing the build on any regression.

CI was also extended to tell the whole repo's story: a fourth job
(`aws-localstack`, secret-guarded via an `aws-guard` job) boots LocalStack, runs
`terraform apply` + `aws/validate.sh` with the duplicate-proof assertion, and destroys
the stack on every push to main when `LOCALSTACK_AUTH_TOKEN` is configured. The token
authenticates the LocalStack workspace only — no AWS account exists.
