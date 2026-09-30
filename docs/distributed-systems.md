# Distributed-Systems Proof

What this system actually guarantees, what it deliberately does not, and where to see
each property demonstrated with real output ([evidence.md](evidence.md)).

---

## Delivery and processing guarantees

```
producer (acks=all, idempotent)
      │
      ▼
Kafka topic "orders" ── broker log (retention and storage apply)
      │
      ▼
consumer group "fulfillment" (at-least-once delivery)
      │
      ├── IdempotentConsumer: eventId seen before? → skip, commit offset
      ├── FailureInjector (demo faults) → exception → DefaultErrorHandler
      │        ├── up to 3 retries after initial delivery (1s/2s/4s)
      │        └── exhausted → DeadLetterPublishingRecoverer → orders.DLT
      └── markFulfilled: safe-to-repeat state transition
```

## 1. Duplicate delivery → suppressed within the running process

Kafka's consumer delivery guarantee is at-least-once, so re-delivery after rebalances
or failed offset commits is expected. `IdempotentConsumer` keeps a
`ConcurrentHashMap.newKeySet()` of processed eventIds for the lifetime of one process;
`Set.add()` returns `false` for a repeat, making check-and-record one atomic step with
no check-then-act race between consumer threads.

```java
public boolean tryProcess(UUID eventId) {
    return processed.add(eventId);
}
```

Demonstrated in [evidence.md](evidence.md) Scenario 2 — the same `eventId` is manually
re-produced while the consumer is running; it logs `[DUPLICATE] ... skipping` and the
stats endpoint still counts one unique event. This is process-local duplicate
suppression, not durable exactly-once processing.

**Limitation:** the set does not survive a restart and is not shared across replicas.
This is acceptable for the demonstration because the downstream effect
(`markFulfilled`) is itself idempotent — the second application of the same state
transition is a no-op. Production replacement: a durable processed-events store
(unique constraint, Redis `SETNX`) or a compacted processed-events topic.

## 2. Consumer downtime → no event loss

Kafka retains records while broker storage remains intact and within retention; the
consumer group's committed offset marks progress. While the consumer is down, produced
records wait on the broker. On consumer restart the group resumes from its committed
offset (or from the beginning with `auto.offset.reset: earliest`, which is set here).
The Compose broker has no persistent volume, so recreating it loses its data; this is
not a broker-disaster-recovery guarantee.

Demonstrated in [evidence.md](evidence.md) Scenario 3 — an order created during a full
consumer stop is fulfilled seconds after the container starts.

## 3. Partial failure → retry with backoff → DLT

A crash between *receive* and *complete* is where naive consumers either lose events or
spin forever. This system's behavior:

| Crash / failure point | Outcome |
|---|---|
| Before idempotency record | Redelivered on restart; processed as first delivery. Safe. |
| Listener throws (e.g. transient outage) | Retried in-process with 1s/2s/4s backoff; if processing recovers within that window the record completes, otherwise it is dead-lettered to `orders.DLT` — no event dropped either way. |
| Retries exhausted (poison record) | Published to `orders.DLT` with failure headers; partition's offset committed; processing continues. |
| After `markFulfilled`, before offset commit | Redelivered, deduped by eventId. No harm. |
| After offset commit | Done; never redelivered. |

Two honest subtleties worth being able to discuss:

- **Injected-failure rollback:** the demo faults fire *after* `tryProcess` records the
  eventId; the consumer removes the record before rethrowing (`IdempotentConsumer.forget`)
  so the retry is processed as a first delivery. Without this, the first attempt would
  have permanently claimed the eventId while having done no work — the classic
  "claim then crash" hole in check-then-act idempotency.
- **The claim-and-crash gap remains in principle:** if the process dies *hard* between
  `tryProcess` and `markFulfilled`, the eventId is lost with the process anyway
  (in-memory), so the redelivery is still processed. With a *durable* dedup store this
  gap would reappear unless the claim is written in the same transaction as the state
  change. That is exactly the tradeoff `docs/audit-trail.md` documents.

## Eventual consistency

Order creation and fulfillment are decoupled by the Kafka log. After `POST /orders`
returns 201, there is a window where the order exists but is not yet fulfilled. The
system converges when the consumer catches up — demonstrated by the downtime scenario:
a `PENDING` order becomes `FULFILLED` without any further client action.

This is the fundamental tradeoff for availability and decoupling: the write path never
waits on the consumer, and the consumer's failures never fail the write path.

## Ordering

Records are keyed by `orderId`, so Kafka guarantees per-order ordering within a
partition. Cross-order ordering is not guaranteed — and is not needed.

## The AWS variant of this system

The same domain flow also exists as a serverless AWS path (SQS → Lambda → DynamoDB +
S3, Terraform-managed, validated against LocalStack). Its idempotency is durable by
construction — the conditional put replaces this path's process-local set — while its
ordering is weaker (no per-key order on a standard queue). The full guarantee
comparison is in [aws-architecture.md](aws-architecture.md).

## References

- Kafka design overview: https://kafka.apache.org/documentation/#design
- Spring Kafka error handling / DLT: https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html
- Idempotent receiver pattern: https://www.enterpriseintegrationpatterns.com/patterns/messaging/IdempotentReceiver.html
- Outbox pattern (the production next step): https://microservices.io/patterns/data/transactional-outbox.html
