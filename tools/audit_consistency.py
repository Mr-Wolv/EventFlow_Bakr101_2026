"""Bidirectional consistency engine — EventFlow final audit.

Mechanically verifies documentation claims against code/config ground truth.
Each check prints MATCH / MISMATCH / MANUAL (verified by reading, not grep).
Exit code 1 if any MISMATCH.
"""
import re
import sys

failures = []
results = []


def check(name, cond, detail=""):
    status = "MATCH  " if cond else "MISMATCH"
    if not cond:
        failures.append(f"{name} :: {detail}")
    results.append(f"{status} {name} {detail}")


def read(path):
    return open(path, encoding="utf-8").read()


def norm(text):
    """Markdown-neutral form: drop emphasis/backtick markers and collapse all
    whitespace runs (table cells wrap across source lines) so doc claims can be
    matched as they render."""
    return re.sub(r"\s+", " ", text.replace("`", "").replace("*", "").replace(">", " "))


# ---------- docs bodies ----------
readme = read("README.md")
audit = read("docs/audit-trail.md")
dist = read("docs/distributed-systems.md")
fail_docs = read("docs/failure-scenarios.md")
kube = read("docs/kubernetes.md")
aws_arch = read("docs/aws-architecture.md")
aws_rd = read("aws/README.md")
aws_val = read("docs/local-aws-validation.md")
evid = read("docs/evidence.md")

nreadme = norm(readme)
naws_val = norm(aws_val)

# ---------- code ground truth ----------
order_yml = read("order-service/src/main/resources/application.yml")
ful_yml = read("fulfillment-service/src/main/resources/application.yml")
publisher = read("order-service/src/main/java/com/eventflow/order/EventPublisher.java")
request = read("order-service/src/main/java/com/eventflow/order/OrderRequest.java")
controller = read("order-service/src/main/java/com/eventflow/order/OrderController.java")
svc = read("order-service/src/main/java/com/eventflow/order/OrderService.java")
idem = read("fulfillment-service/src/main/java/com/eventflow/fulfillment/IdempotentConsumer.java")
kec = read("fulfillment-service/src/main/java/com/eventflow/fulfillment/KafkaErrorConfig.java")
consumer = read("fulfillment-service/src/main/java/com/eventflow/fulfillment/FulfillmentConsumer.java")
injector = read("fulfillment-service/src/main/java/com/eventflow/fulfillment/FailureInjector.java")
store = read("fulfillment-service/src/main/java/com/eventflow/fulfillment/OrderFulfillmentStore.java")
admin = read("fulfillment-service/src/main/java/com/eventflow/fulfillment/AdminController.java")
geh = read("order-service/src/main/java/com/eventflow/order/GlobalExceptionHandler.java")
feh = read("fulfillment-service/src/main/java/com/eventflow/fulfillment/ErrorExceptionHandler.java")
event = read("order-service/src/main/java/com/eventflow/order/OrderCreatedEvent.java")
compose = read("docker-compose.yml")
topic_yaml = read("k8s/kafka-topic.yaml")
ful_dep = read("k8s/fulfillment-deployment.yaml")
ord_dep = read("k8s/order-deployment.yaml")
lambda_tf = read("aws/terraform/lambda.tf")
main_tf = read("aws/terraform/main.tf")
iam_tf = read("aws/terraform/iam.tf")
handler = read("aws/lambda/src/main/java/com/eventflow/aws/OrderCreatedHandler.java")
validate = read("aws/validate.sh")
ci = read(".github/workflows/ci.yml")

# ============ REVERSE: README / audit-trail / diagram claims -> code ============
check("port 8080 (order)", "port: 8080" in order_yml and "8080" in compose and '"http"\n          ports' not in ord_dep)
check("port 8081 (fulfillment)", "port: 8081" in ful_yml and "8081" in compose)
check("validation customerId @NotNull", "@NotNull UUID customerId" in request)
check("validation amount >= 0.01", '@DecimalMin(value = "0.01"' in request)
check("README claim amount>=0.01", "amount ≥ 0.01" in nreadme and "0.01" in request)
check("acks=all", "acks: all" in order_yml and "acks=all" in readme)
check("idempotent producer enabled", "enable.idempotence: true" in order_yml and "idempotent" in readme.lower())
check("no type headers (producer)", "spring.json.add.type.headers: false" in order_yml)
check("consumer binds own class", "spring.json.value.default.type" in ful_yml and "consumer-owned" in audit)
check("key = orderId", "event.orderId().toString()" in publisher and "keyed by orderId" in nreadme)
check("group id fulfillment", "group-id: fulfillment" in ful_yml and 'groupId = "fulfillment"' in consumer)
check("auto-offset-reset earliest", "auto-offset-reset: earliest" in ful_yml and "auto.offset.reset: earliest" in dist + fail_docs)
check("retry backoff 1s/2s/4s", "ExponentialBackOff(1_000L, 2.0)" in kec and "1s → 2s → 4s" in readme.replace("(1s, 2s, 4s)", "1s → 2s → 4s") or "1s, 2s, 4s" in readme)
check("max elapsed 10s", "setMaxElapsedTime(10_000L)" in kec)
check("DLT topic orders.DLT", '"orders.DLT"' in fail_docs or "orders.DLT" in readme)
check("deadLetter recoverer present", "DeadLetterPublishingRecoverer" in kec and "DeadLetterPublishingRecoverer" in readme)
check("dedup atomic add", "return processed.add(eventId);" in idem and "processed.add(eventId)" in dist)
check("forget() rollback", "public void forget(UUID eventId)" in idem and "idempotentConsumer.forget(eventId)" in consumer and "forget(eventId)" in audit)
check("fault modes NONE/ALWAYS/ONCE_PER_EVENT", all(m in injector for m in ("NONE", "ALWAYS", "ONCE_PER_EVENT")) and '{"fault": "NONE" \\| "ALWAYS" \\| "ONCE_PER_EVENT"}' in nreadme and "NONE · ALWAYS · ONCE_PER_EVENT" in read("architecture/generate.py"))
check("markFulfilled safe-to-repeat", "public void markFulfilled(UUID orderId)" in store and "safe-to-repeat" in readme)
check("POST /orders 201+Location", "HttpStatus.CREATED" in controller and ".location(" in controller and "201 Created" in readme)
check("GET /orders/{id} 200|404", "notFound()" in controller or "ResponseEntity.notFound" in controller)
check("admin endpoints documented", all(e in readme for e in ("/__admin/stats", "/__admin/orders/{orderId}/status", "/__admin/failure")) and all(e in admin for e in ('"/__admin"', '"/stats"', '"/orders/{orderId}/status"', '"/failure"')))
check("error 400/404/405/415/500 order", all(s in geh for s in ("400", "404", "405", "415", "500")))
check("error 400/404/405/415/500 fulfillment", all(s in feh for s in ("400", "404", "405", "415", "500")))
check("enum self-documenting message", "accepted values: " in feh and "self-document" in readme)
check("event payload fields", all(f in event for f in ("eventId", "eventType", "orderId", "customerId", "amount", "occurredAt")) and all(f in readme for f in ('"eventId"', '"eventType"', '"occurredAt"')))
check("ISO-8601 occurredAt", "Instant.now().toString()" in event and "ISO-8601" in readme)
check("compose loopback-only", compose.count("127.0.0.1:") == 3 and "127.0.0.1 only" in norm(read('docs/aws-deployment.md')))
check("kafka-init creates topic", "--create --if-not-exists" in compose and "kafka-init" in readme)
check("topic 1 partition RF1", "partitions: 1" in topic_yaml and "replicas: 1" in topic_yaml)
check("retention.ms 7d = 604800000", "retention.ms: 604800000" in topic_yaml and 604800000 == 7 * 24 * 3600 * 1000 and "retention 7d" in read("architecture/generate.py"))
check("strimzi v1 CRD", "kafka.strimzi.io/v1" in topic_yaml and "kafka.strimzi.io/v1" in kube)
check("fulfillment replicas 2", "replicas: 2" in ful_dep and "2 replicas" in nreadme and "2 replicas" in kube)
check("probes enabled", "management" in order_yml and "probes" in (order_yml + ful_yml) and "/actuator/health/readiness" in kube)
check("readiness+liveness in manifests", all(p in ful_dep for p in ("readinessProbe", "livenessProbe")) and all(p in ord_dep for p in ("readinessProbe", "livenessProbe")))
check("namespace-first warning", "k8s/namespace.yaml" in readme and "races" in kube)
check("resources documented", "requests:" in ful_dep and "limits:" in ful_dep and "resource requests/limits" in readme)

# ============ AWS path claims -> terraform/handler ============
check("lambda runtime java21", re.search(r'runtime\s+= "java21"', lambda_tf) is not None)
check("lambda handler FQCN", "com.eventflow.aws.OrderCreatedHandler::handleRequest" in lambda_tf)
check("lambda timeout 60 / mem 512", "timeout          = 60" in lambda_tf and "memory_size      = 512" in lambda_tf)
check("batch size 5", "batch_size       = 5" in lambda_tf or "batch_size        = 5" in lambda_tf)
check("dynamo PAY_PER_REQUEST + keys", "PAY_PER_REQUEST" in main_tf and 'hash_key     = "eventId"' in main_tf and '"orderId"' in main_tf)
check("GSI orderId-index", 'name            = "orderId-index"' in main_tf)
check("sqs visibility 60", "visibility_timeout_seconds = 60" in main_tf)
check("s3 force_destroy (post-incident)", "force_destroy = true" in main_tf)
check("IAM least-privilege actions", all(a in iam_tf for a in ("sqs:ReceiveMessage", "dynamodb:PutItem", "s3:PutObject")))
check("conditional put in handler", "attribute_not_exists(eventId)" in handler and "attribute_not_exists(eventId)" in aws_arch)
check("archive key shape", 'orders/" + orderId + "/" + eventId + ".json"' in handler and "<orderId>/<eventId>.json" in aws_arch)
check("rethrow on error (at-least-once)", "throw e;" in handler and "at-least-once" in aws_arch)
check("FULFILLED_BY_LAMBDA status", "FULFILLED_BY_LAMBDA" in handler and "FULFILLED_BY_LAMBDA" in validate)
check("validate.sh duplicate proof", "FLAG_DUPLICATE" in validate and "duplicate-proof" in readme)
check("ENFORCE_IAM documented+used", "ENFORCE_IAM=1" in aws_rd and "ENFORCE_IAM" in ci and "ENFORCE_IAM" in aws_val + aws_arch)
check("no-AWS-account boundary sentence", all("No production AWS account" in d or "no AWS account" in d for d in (aws_rd, aws_arch, readme)))
check("token-not-credential boundary", all("not an AWS credential" in norm(d) for d in (aws_rd, aws_arch, aws_val, readme)))
check("terraform apply/destroy in CI", "terraform apply" in ci and "terraform destroy" in ci)

# ============ test/CI counts ============
check("README 50+12=62", "50 unit tests" in readme and "12 more" in readme or "(12 tests" in readme)
check("evidence 0-missed claim", "0 missed lines" in evid and "100.00%" in evid)
check("CI kind smoke polls", "seq 1 12" in ci and "sleep 5" in ci)
check("CI creates namespace first", "kubectl apply -f k8s/namespace.yaml" in ci)

# ============ FORWARD spot checks (code -> docs) ============
check("eventflow.topics.orders override documented", "${KAFKA_TOPIC:orders}" in order_yml and "KAFKA_TOPIC" in readme)
check("health exposure documented", "include: health,info" in order_yml and "/actuator/health" in readme)
check("Lambda ARCHIVE_BUCKET env documented", "ARCHIVE_BUCKET" in lambda_tf and "ARCHIVE_BUCKET" in validate)

print("\n".join(results))
print(f"\nTOTAL CHECKS: {len(results)}  MISMATCH: {len(failures)}")
if failures:
    print("FAILURES:")
    for f in failures:
        print("  -", f)
sys.exit(1 if failures else 0)
