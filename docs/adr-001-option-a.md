# ADR-001 — Persistence strategy: preserve in-memory scope (Option A)

**Date:** 2026-10-28  
**Status:** Accepted  
**Decision:** Keep EventFlow’s order and fulfillment state in-memory. Do not introduce PostgreSQL or an outbox/inbox durable store in the current milestone.

## Context

EventFlow is a deliberately compact distributed-systems demonstration. The current
implementation uses in-memory repositories and a process-local `ConcurrentHashMap`
dedup set. P0 hardening proved the existing crash boundaries, bounded retries, DLT
behavior, and admin profile gating — all without changing persistence architecture.

Option B (durable Kafka path) would introduce:
- PostgreSQL with transactional outbox writes
- Durable inbox/dedup records with uniqueness constraints
- Crash-safe offset/effect atomicity

This is coherent, but it adds a second runtime dependency, a schema, migration
tooling, and a longer setup path for the exact audience the project targets
(interviewers running a five-minute demo).

## Decision

Choose **Option A** for the current milestone.

### Rationale

- The project’s stated goal is a **compact lab**, not a production reference.
- The README and `docs/audit-trail.md` already disclose the in-memory limitations
  honestly, with explicit “Production fix” notes.
- P0 tests now prove **exactly** where effects can be lost or repeated, which is
  more valuable for a distributed-systems discussion than hiding the boundaries
  behind a database.
- Docker Compose stays self-contained (Kafka + two JVMs); no volume mounts,
  no schema migrations, no ORM tuning.
- The AWS serverless variant already demonstrates durable idempotency
  (`attribute_not_exists(eventId)` in DynamoDB), so the comparison narrative
  remains intact.

## Consequences

- Orders and dedup state are lost on service restart. This is a **scope
  limitation**, not a bug.
- Redelivery after a crash reprocesses events; `markFulfilled` is safe to repeat.
- If the project later needs durable state, the ADR path is clear: add outbox +
  inbox in a versioned v2 milestone, with the six crash-boundary tests already
  enumerated in the hardening plan.

## Non-goals

- No claim of exactly-once delivery.
- No replacement of Kafka with AWS services or vice versa.
- No database added “because real applications use databases.”

## Review

Revisit this decision only if the project’s story changes from
“compact interview lab” to “production pattern reference.”
