# Audit Trail — Processing Contract

The exact lifecycle of one `OrderCreated` record, from accept to done, with the state
left behind at every step.

---

## Write path (Order Service)

```
POST /orders   → bean validation (@NotNull customerId, amount ≥ 0.01) → 400 on failure
  → Order saved to in-memory repository
  → OrderCreatedEvent built (fresh eventId, ISO-8601 timestamp)
  → KafkaTemplate.send(topic, key = orderId, event)
  → 201 Created + Location immediately (does not wait for the broker)
  → async callback logs [PUBLISHED] or [PUBLISH-FAILED]
```

Producer settings: `acks=all`, idempotent producer enabled — the broker acknowledges
once per logical record even across producer retries, so Kafka-side duplicates from the
producer are already excluded. Duplicates demonstrated in the consumer tests come from
the delivery side (the redelivery scenario Kafka consumers must always expect).

## Consume path (Fulfillment Service)

```
@KafkaListener(topics = "${eventflow.topics.orders:orders}", groupId = "fulfillment")
  1. IdempotentConsumer.tryProcess(eventId)
      ├─ false → [DUPLICATE] skip (within this process) → listener returns → offset committed
       └─ true  → eventId recorded
  2. FailureInjector.maybeFail(eventId)
       └─ fault armed → forget(eventId) → throw → DefaultErrorHandler
             ├─ up to 3 retries after initial delivery (1s/2s/4s backoff, [RETRY] logged)
             └─ exhausted → DeadLetterPublishingRecoverer → orders.DLT → offset committed
  3. OrderFulfillmentStore.markFulfilled(orderId)
       → [PROCESSING] … [FULFILLED] logged
       → listener returns normally → offset committed
```

## Offset commit strategy

Spring Kafka commits the offset **after the listener returns successfully** (or after
the error handler finishes with a record). Consequences:

- Listener throws → no commit → Kafka redelivers → the retry/recovery scenario.
- Duplicate skipped → normal return → commit → the duplicate is never seen again.
- DLT-published → commit → the partition is never blocked by a poison record.

## What each restart loses (in-memory scope)

| State | Lost on restart? | Consequence | Production fix |
|---|---|---|---|
| Orders (order-service) | Yes | `GET /orders/{id}` 404s after restart | PostgreSQL/JPA |
| Idempotency set | Yes | Redeliveries after restart processed again | Durable processed-events store |
| Fulfillment states | Yes | Status endpoint reports PENDING for old orders | PostgreSQL/JPA |

These limitations bound what survives an application restart. Kafka retains records
and offsets while broker storage remains intact and within retention. The Compose
broker has no persistent volume, so recreating that broker also loses its log.

## Known gaps (intentional, with upgrade paths)

> The AWS serverless variant (SQS → Lambda → DynamoDB, LocalStack-validated) enforces
> the idempotency guarantee *durably* — the Lambda's conditional put
> (`attribute_not_exists(eventId)`) survives restarts and spans invocations, unlike the
> in-memory set below. Guarantee comparison: [aws-architecture.md](aws-architecture.md).

1. Idempotency + fulfillment state are in-memory — see table above.
2. No transactional outbox — the DB save and the publish are two steps; a crash between
   them loses the event (and the order is gone anyway in-memory). Fix: outbox pattern.
3. Single-partition topic caps consumer parallelism at 1. Fix: partition by `orderId`.
4. No schema registry / schema evolution — plain JSON, consumer-owned event copies.
5. No DLQ *replay* tooling — records land in `orders.DLT`; reprocessing is manual
   (`kafka-console-consumer` → fix → re-produce). A replay job is the next step.
6. No distributed tracing / correlation IDs — logs carry eventId/orderId, which is
   greppable but not traceable. Fix: Micrometer Tracing + OpenTelemetry.
7. Observability endpoints are replica-local — `/__admin/stats` and
   `GET /__admin/orders/{id}/status` read the local in-memory store, so behind a
   load-balanced Service (K8s ClusterIP, N replicas) a query can land on a replica that
   never saw the event and report PENDING/zero (observed live during the minikube run).
   Fix: shared state store, or a service that fans out to all replicas.
