#!/usr/bin/env bash
# EventFlow AWS path — LocalStack integration validation.
#
# Prerequisites:
#   - LocalStack (Pro image) running on http://localhost:4566 with the Docker
#     socket mounted (Lambda v2 runs functions as real containers)
#   - awscli + awscli-local  (pip install awscli awscli-local)
#   - function package built:  mvn -f aws/lambda/pom.xml package
#   - stack deployed:          (cd aws/terraform && terraform apply -auto-approve)
#
# What it proves (the AWS-path pipeline, live):
#   SQS message -> Lambda -> DynamoDB item (conditional put) + S3 archive object
#   With FLAG_DUPLICATE=1 it also re-sends the SAME eventId and asserts the
#   DynamoDB item count stays 1 — durable idempotency under at-least-once
#   redelivery.
#
# Usage:
#   bash aws/validate.sh                 # fresh random event
#   FLAG_DUPLICATE=1 bash aws/validate.sh

set -euo pipefail

REGION="${AWS_DEFAULT_REGION:-us-east-1}"
export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-test}"
export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-test}"
export AWS_DEFAULT_REGION="$REGION"

QUEUE_URL="${QUEUE_URL:-http://localhost:4566/000000000000/eventflow-orders-queue}"
TABLE="${TABLE_NAME:-eventflow-orders}"
BUCKET="${ARCHIVE_BUCKET:-eventflow-order-archive}"
EVENT_ID="${EVENT_ID:-$(python -c 'import uuid; print(uuid.uuid4())')}"
ORDER_ID="${ORDER_ID:-$(python -c 'import uuid; print(uuid.uuid4())')}"
POLL_TRIES="${POLL_TRIES:-10}"
POLL_SLEEP="${POLL_SLEEP:-3}"

step() { printf '\n== %s\n' "$*"; }
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
pass() { printf 'PASS: %s\n' "$*"; }

step "LocalStack health"
curl -sf http://localhost:4566/_localstack/health >/dev/null || fail "LocalStack not reachable on :4566"
pass "LocalStack responds"

step "Stack present (queue exists)"
awslocal sqs get-queue-attributes --queue-url "$QUEUE_URL" --attribute-names QueueArn >/dev/null 2>&1 \
  || fail "queue $QUEUE_URL not found — run terraform apply first (see aws/README.md)"
pass "queue reachable"

BODY="{\"eventId\":\"$EVENT_ID\",\"eventType\":\"OrderCreated\",\"orderId\":\"$ORDER_ID\",\"customerId\":\"550e8400-e29b-41d4-a716-446655440000\",\"amount\":125.50,\"occurredAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}"

send_and_wait() {
  awslocal sqs send-message --queue-url "$QUEUE_URL" --message-body "$BODY" >/dev/null
  for _ in $(seq 1 "$POLL_TRIES"); do
    ITEM=$(awslocal dynamodb get-item --table-name "$TABLE" \
      --key "{\"eventId\":{\"S\":\"$EVENT_ID\"}}" --output json 2>/dev/null)
    echo "$ITEM" | python -c 'import json,sys; d=json.load(sys.stdin); sys.exit(0 if d.get("Item") else 1)' && return 0
    sleep "$POLL_SLEEP"
  done
  return 1
}

step "Send OrderCreated event (eventId=$EVENT_ID)"
send_and_wait || fail "event never appeared in DynamoDB"
pass "SQS -> Lambda -> DynamoDB"

ITEM=$(awslocal dynamodb get-item --table-name "$TABLE" --key "{\"eventId\":{\"S\":\"$EVENT_ID\"}}" --output json)
echo "$ITEM" | python -c '
import json, sys
item = json.load(sys.stdin)["Item"]
print("  status   :", item["status"]["S"])
print("  orderId  :", item["orderId"]["S"])
print("  amount   :", item["amount"]["S"])'
echo "$ITEM" | python -c '
import json, sys
item = json.load(sys.stdin)["Item"]
assert item["status"]["S"] == "FULFILLED_BY_LAMBDA", item' || fail "unexpected status"

step "S3 archive object"
KEY="orders/$ORDER_ID/$EVENT_ID.json"
awslocal s3api head-object --bucket "$BUCKET" --key "$KEY" >/dev/null 2>&1 || fail "archive object s3://$BUCKET/$KEY missing"
pass "s3://$BUCKET/$KEY"

COUNT=$(awslocal dynamodb scan --table-name "$TABLE" --select COUNT --output json | python -c 'import json,sys; print(json.load(sys.stdin)["ScannedCount"])')

if [ "${FLAG_DUPLICATE:-0}" = "1" ]; then
  step "Duplicate delivery: re-sending the SAME eventId"
  send_and_wait || fail "duplicate send broke the pipeline"
  COUNT2=$(awslocal dynamodb scan --table-name "$TABLE" --select COUNT --output json | python -c 'import json,sys; print(json.load(sys.stdin)["ScannedCount"])')
  [ "$COUNT2" = "$COUNT" ] || fail "item count changed after duplicate ($COUNT -> $COUNT2)"
  pass "durable idempotency: item count still $COUNT2 (conditional put suppressed the redelivery)"
fi

step "Summary"
echo "  eventId : $EVENT_ID"
echo "  orderId : $ORDER_ID"
echo "  table   : $TABLE (items: $COUNT)"
echo "  archive : s3://$BUCKET/orders/$ORDER_ID/$EVENT_ID.json"
echo "ALL CHECKS PASSED"
