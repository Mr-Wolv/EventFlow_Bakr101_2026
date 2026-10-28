# Claim Matrix — EventFlow P0 Hardening

Updated: 2026-10-28  
Commit: `e52b9ea`

| Guarantee | Implementation location | Automated regression test | Live demonstration / probe | Limitation |
|---|---|---|---|---|
| `POST /orders` returns `201` + `Location` | `OrderController`, `OrderService` | `OrderControllerTest` | Compose smoke §8, kind CI job | In-memory order store lost on restart |
| `GET /orders/{id}` returns `200` or `404` | `OrderController`, `OrderRepository` | `OrderControllerTest` | Curl against Compose / kind | In-memory store |
| `customerId` UUID + `amount ≥ 0.01` validated | `OrderRequest` + `GlobalExceptionHandler` | `GlobalExceptionHandlerTest`, `OrderControllerTest` | Bad-payload curl → `400` | Validation is server-side only |
| `OrderCreated` event has versioned contract (`eventVersion=1`) | `OrderCreatedEvent` (both services) | `EventPublisherTest`, `FulfillmentConsumerTest` | `kafka-console-producer` replay | Consumer rejects `eventVersion ≠ 1` with `IllegalArgumentException` |
| Producer is idempotent + `acks=all` | `application.yml`, `EventPublisher` | `EventPublisherTest` | Docker logs show `[PUBLISHED]` with partition/offset | Fire-and-forget from client perspective |
| Event keyed by `orderId` for per-order partition ordering | `EventPublisher.publish()` | `EventPublisherTest` | Logs show `orders-0@N` | Single partition → one consumer replica active |
| Duplicate event skipped while consumer process is alive | `FulfillmentConsumer`, `IdempotentConsumer` | `FulfillmentConsumerTest.duplicateDeliveryIsSkipped`, `IdempotentConsumerTest.firstDeliveryAcceptedSecondRejected` | §2 duplicate redelivery → `[DUPLICATE]` | Dedup set lost on restart; redelivery reprocessed |
| Duplicate after restart is reprocessed (process-local scope) | `IdempotentConsumer` (no persistence) | `IdempotentConsumerTest.dedupStateIsProcessLocal` | §3 consumer downtime + restart | In-memory only |
| Injected failure rolls back idempotency record and retries | `FulfillmentConsumer`, `FailureInjector`, `KafkaErrorConfig` | `FulfillmentConsumerTest.injectedFailureRollsBackIdempotencyAndRethrows`, `FailureInjectorTest` | §4 ONCE_PER_EVENT fault → retry → recovery | Bounded to 3 retries (1s/2s/4s) |
| Exhausted retry publishes to `orders.DLT` and commits offset | `KafkaErrorConfig`, `FulfillmentConsumer` | `KafkaErrorConfigTest`, `FulfillmentConsumerTest` | §5 ALWAYS fault → `orders.DLT` record | DLT replay is manual |
| Effect then listener throw keeps dedup record; redelivery harmless | `FulfillmentConsumer`, `IdempotentConsumer` | `FulfillmentConsumerTest.successfulEffectThenListenerThrowKeepsDedupRecord` | Documented in `docs/audit-trail.md` | Proved at unit level; Kafka redelivery semantics are Spring-managed |
| Admin endpoints disabled by default | `AdminController`, `application-demo.yml`, `docker-compose.yml` | `AdminControllerDisabledByDefaultTest`, `AdminControllerTest` | Compose sets `SPRING_PROFILES_ACTIVE=demo` | Disabled unless `eventflow.admin.enabled=true` AND `demo` profile |
| Stats / failure endpoints expose no secrets | `AdminController` | `AdminControllerTest` | `/__admin/stats` → counts + timestamps only | Local replica state only |
| Health/readiness exposed for K8s probes | `application.yml`, `k8s/` manifests | N/A (Spring Boot actuator) | `curl /actuator/health` → `UP` | Does not expose payloads or internals |
| Kubernetes 2-replica deployment with probes + resource limits | `k8s/fulfillment-deployment.yaml`, `k8s/order-deployment.yaml` | N/A (infrastructure) | kind CI smoke test | Single partition caps consumer parallelism at 1 |
| AWS Lambda path: conditional-put idempotency | `aws/lambda/src/main/java/.../OrderCreatedHandler` | Lambda module (12 tests) | `aws/validate.sh` against LocalStack | LocalStack evidence only; no AWS account |
| Terraform IAM least-privilege | `aws/terraform/iam.tf` | Lambda build + `terraform plan` in CI | `terraform apply` in `aws-localstack` job | Gated by `LOCALSTACK_AUTH_TOKEN` secret |
| CI runs same validators as local | `.github/workflows/ci.yml`, `tools/audit_consistency.py` | `mvn -B verify` + audit script + Docker build | GitHub Actions badge | Scheduled jobs are not currently configured |
