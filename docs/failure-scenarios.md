# Failure Scenarios — Reproduction Steps

Every scenario below is reproduced with captured output in [evidence.md](evidence.md).

Prerequisite: `docker compose up --build` and all containers healthy.

> **Windows (Git Bash) note:** prefix `docker compose exec` commands that pass
> container paths with `MSYS_NO_PATHCONV=1` and add `-T`, e.g.
> `MSYS_NO_PATHCONV=1 docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh ...`
> — otherwise Git Bash rewrites `/opt/...` into a Windows path.

---

## Scenario 1: Happy Path

The baseline: a created order flows through Kafka and gets fulfilled.

```bash
curl -s -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId": "550e8400-e29b-41d4-a716-446655440000", "amount": 125.50}'
# → 201 Created + Location: /orders/<id>

docker compose logs order-service      | grep PUBLISHED   # event on orders-p0@<offset>
docker compose logs fulfillment-service | grep FULFILLED # [FULFILLED] order <id>
curl -s http://localhost:8081/__admin/orders/<orderId>/status  # {"status":"FULFILLED"}
```

---

## Scenario 2: Duplicate Event Delivery

At-least-once delivery means a consumer may see the same event more than once (for
example after a rebalance or failed offset commit). The demo manually re-produces an
event with the same ID to exercise the process-local dedup set; this is not a durable
exactly-once guarantee.

**Step 1 — Create an order and note the event's offset in the order-service log:**

```bash
curl -s -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId": "550e8400-e29b-41d4-a716-446655440000", "amount": 10.00}'
docker compose logs order-service | grep PUBLISHED   # note the eventId and offset
```

**Step 2 — Produce that exact event record again (simulating Kafka redelivery):**

```bash
MSYS_NO_PATHCONV=1 docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic orders <<'EOF'
{"eventId":"<SAME-EVENT-ID>","eventType":"OrderCreated","orderId":"<SAME-ORDER-ID>","customerId":"550e8400-e29b-41d4-a716-446655440000","amount":10.00,"occurredAt":"<SAME-TIMESTAMP>"}
EOF
```

**Step 3 — Observe the consumer:**

```bash
docker compose logs fulfillment-service | grep DUPLICATE
# [DUPLICATE] event <id> already processed — skipping
curl -s http://localhost:8081/__admin/stats
# "uniqueEventsProcessed": 1   ← not 2
```

Note: calling `POST /orders` twice does **not** create a duplicate — each call creates
a new order with a new `eventId`, which is two distinct events, correctly processed twice.

---

## Scenario 3: Consumer Temporarily Unavailable

Kafka persists events and tracks the consumer group's committed offset, so events
created while the consumer is down must be processed when it returns.

```bash
docker compose stop fulfillment-service

curl -s -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId": "550e8400-e29b-41d4-a716-446655440000", "amount": 99.99}'
# order created — no FULFILLED anywhere

docker compose start fulfillment-service
sleep 10
docker compose logs fulfillment-service | grep -E "PROCESSING|FULFILLED"
# [PROCESSING] order <id> amount 99.99 ...
# [FULFILLED] order <id>
```

Works because of `auto-offset-reset: earliest` plus the broker's retained log: the group
resumes from its last committed offset while broker storage remains intact and the
record is within retention.

---

## Scenario 4: Partial Failure — Retry and Recovery

A failure *after* the event is received but *before* any state change. The failure
injector makes this deterministic instead of "kill it at the right millisecond".

```bash
# Arm a one-shot fault for each event's first delivery attempt
curl -s -X POST http://localhost:8081/__admin/failure \
  -H "Content-Type: application/json" -d '{"fault": "ONCE_PER_EVENT"}'

curl -s -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId": "550e8400-e29b-41d4-a716-446655440000", "amount": 55.00}'
```

What happens, in order:

1. First delivery throws → `[FAULT-INJECTED] ... (once-per-event fault armed)`
2. The consumer rolls back the idempotency record and the error handler retries:
   `[RETRY] attempt 1/2/3 ...` with 1s → 2s → 4s backoff
3. Redelivery succeeds (fault was one-shot per event) → `[FULFILLED]`

```bash
curl -s http://localhost:8081/__admin/orders/<orderId>/status
# {"orderId":"...","status":"FULFILLED"}
```

---

## Scenario 5: Retry Exhaustion → Dead-Letter Topic

When retries cannot fix the failure, the record must not block the partition forever.

```bash
curl -s -X POST http://localhost:8081/__admin/failure \
  -H "Content-Type: application/json" -d '{"fault": "ALWAYS"}'

curl -s -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId": "550e8400-e29b-41d4-a716-446655440000", "amount": 75.00}'
# Initial delivery plus up to 3 retries (1s/2s/4s) fail → record published to orders.DLT, offset committed
```

Verify the dead-letter record and that processing continued afterwards:

```bash
MSYS_NO_PATHCONV=1 docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic orders.DLT --from-beginning --max-messages 1
# {"eventId":...}  — DeadLetterPublishingRecoverer also attaches kafka_original* and
#                    kafka_exception* headers (inspect with --property print.headers=true)

curl -s -X POST http://localhost:8081/__admin/failure/clear
# next order processes normally — the partition was never stuck
```

---

## Why this design

See [distributed-systems.md](distributed-systems.md) for the guarantee analysis, and
[audit-trail.md](audit-trail.md) for the processing-contract walkthrough (what is
committed when, and what happens if the process dies at each step).
