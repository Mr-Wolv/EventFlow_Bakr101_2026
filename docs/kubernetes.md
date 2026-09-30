# Kubernetes Deployment

> **Historical verification:** this flow (Minikube v1.39.0 + Strimzi single-node Kafka
> + these manifests, including scale-up, pod-kill failover and consumer-group rebalance)
> was executed with Java 21 on 2026-09-29 — captured in [evidence.md](evidence.md) §6.
> On Java 25, CI deploys the same manifests to a kind cluster with Strimzi Kafka and
> verifies an in-cluster order → FULFILLED smoke test on every push to main (§9).

## Prerequisites

- A local cluster: Minikube, kind, or Docker Desktop's Kubernetes
- `kubectl` pointed at that cluster
- Locally built images:

```bash
docker build -t eventflow/order-service:latest      -f order-service/Dockerfile      .
docker build -t eventflow/fulfillment-service:latest -f fulfillment-service/Dockerfile .
```

With Minikube, load them into the cluster daemon:

```bash
eval $(minikube docker-env)   # or: minikube image load eventflow/order-service:latest
```

> **Tag gotcha (hit for real during deployment):** the manifests reference
> `eventflow/order-service` with a slash. If you built the images via
> `docker compose build`, they are named `eventflow-order-service` with dashes —
> retag before loading:
>
> ```bash
> docker tag eventflow-order-service:latest eventflow/order-service:latest
> docker tag eventflow-fulfillment-service:latest eventflow/fulfillment-service:latest
> minikube image load eventflow/order-service:latest eventflow/fulfillment-service:latest
> ```
>
> Also apply `k8s/namespace.yaml` **before** `kubectl apply -f k8s/` — a single
> directory apply races the namespace creation against the objects that reference it.

## Kafka

The manifests consume `KAFKA_BOOTSTRAP_SERVERS` from the `eventflow-config` ConfigMap,
which defaults to the Strimzi bootstrap below (verified working). Two supported paths:

**Option A — Strimzi (Kafka fully in-cluster, the showcase option):**

This follows the [official Strimzi quickstart](https://strimzi.io/quickstarts/), so the
CRD shapes below stay current with the operator instead of drifting:

```bash
kubectl create namespace kafka
kubectl create -f 'https://strimzi.io/install/latest?namespace=kafka' -n kafka

# watch the operator come up
kubectl get pod -n kafka --watch

# official single-node (KRaft) Kafka cluster example
kubectl apply -f https://strimzi.io/examples/latest/kafka/kafka-single-node.yaml -n kafka
kubectl wait kafka/my-cluster --for=condition=Ready --timeout=300s -n kafka
```

The topic — the Strimzi equivalent of the compose `kafka-init` container (the broker
would also auto-create it, but explicit is the point here):

The equivalent `KafkaTopic` CR ships in this repo as [k8s/kafka-topic.yaml](../k8s/kafka-topic.yaml).
Note the apiVersion: current Strimzi releases serve these CRDs as `kafka.strimzi.io/v1`
(older `v1beta2` is no longer served — verified against the live CRD during deployment):

```yaml
apiVersion: kafka.strimzi.io/v1
kind: KafkaTopic
metadata:
  name: orders
  namespace: kafka
  labels:
    strimzi.io/cluster: my-cluster
spec:
  partitions: 1
  replicas: 1
```

Then point the ConfigMap at Strimzi:

```yaml
# k8s/configmap.yaml
data:
  KAFKA_BOOTSTRAP_SERVERS: "my-cluster-kafka-bootstrap.kafka.svc.cluster.local:9092"
```

**Option B — Compose Kafka from local dev:** keep Kafka in Docker Compose and run only
the services in K8s. Point the ConfigMap at `host.docker.internal:9092`. Note: the
compose file publishes Kafka on `127.0.0.1` only (deliberate), and whether a
cluster-on-Docker can reach a loopback-published port depends on the platform's
host-gateway plumbing. If the pods cannot connect, temporarily publish the broker on
all interfaces (`"9092:9092"`) for the session — which is exactly why loopback is the
default.

## Deploy

```bash
kubectl apply -f k8s/namespace.yaml   # first — a single directory apply races namespace creation
kubectl apply -f k8s/                 # everything else
kubectl apply -f k8s/kafka-topic.yaml # the Strimzi KafkaTopic (lives in the kafka namespace)
```

Deployed objects:

| Object | Purpose |
|---|---|
| Namespace `eventflow` | Isolation of the whole system |
| ConfigMap `eventflow-config` | Kafka bootstrap address, topic name, log level |
| Deployment `order-service` | 1 replica, readiness + liveness probes on `/actuator/health/*`, resource requests/limits |
| Service `order-service` | Stable ClusterIP virtual IP + DNS over changing pods |
| Deployment `fulfillment-service` | 2 replicas — consumer group spreads partitions across members |
| Service `fulfillment-service` | ClusterIP for the admin/stats endpoints |
| `KafkaTopic orders` (Strimzi CR, `kafka` ns) | Topic managed by the operator — the K8s-native equivalent of the compose `kafka-init` container |

Verify:

```bash
kubectl get all -n eventflow
# pod/order-service-xxx            1/1  Running
# pod/fulfillment-service-xxx      1/1  Running
# pod/fulfillment-service-yyy      1/1  Running
# service/order-service            ClusterIP 8080/TCP
# service/fulfillment-service      ClusterIP 8081/TCP
# deployment.apps/order-service    1/1
# deployment.apps/fulfillment-service  2/2
```

## Probes

Both deployments use Spring Boot's dedicated probe groups (`management.endpoint.health.probes.enabled: true`):

- **readinessProbe** → `GET /actuator/health/readiness` — no traffic until the app reports ready
- **livenessProbe** → `GET /actuator/health/liveness` — container is restarted only when liveness fails

**Honest scope of these probes:** Spring Boot has no out-of-the-box Kafka health
indicator, so both groups here check JVM/application availability only — a Kafka outage
does *not* flip readiness or liveness. That is deliberate: broker blips would otherwise
flap pods, and the consumer's internal retry/backoff already handles broker hiccups.
The real value of the split is protective: `liveness` stays availability-only, so any
health indicator added later (a database, a custom Kafka check) can degrade *readiness*
without ever triggering restart storms. Production upgrade if you want traffic gated on
broker connectivity: a custom `HealthIndicator` contributing to the readiness group only.

Note on layout: there is no standalone `k8s/probes.yaml` — probes are defined inline in
both Deployment manifests, which keeps each workload self-contained.

## Scaling and self-healing

```bash
# Replica management — Kubernetes converges to the requested count
kubectl scale deployment fulfillment-service --replicas=3 -n eventflow
kubectl get pods -n eventflow -w

# Self-healing — delete a pod, watch it get recreated
kubectl delete pod -l app=fulfillment-service -n eventflow
kubectl get pods -n eventflow
```

With the demo's single-partition topic, one replica consumes and the others sit idle —
expected consumer-group behavior (observed live: exactly one member received
`orders-0`; its logs show `partitions assigned: [orders-0]` while the sibling shows
`[]`). Scale `orders` partitions to scale processing out.

## Test through the cluster

Either port-forward from the host:

```bash
kubectl port-forward svc/order-service 8080:8080 -n eventflow
curl -s -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId": "550e8400-e29b-41d4-a716-446655440000", "amount": 125.50}'
```

or execute inside the cluster (exercises Service DNS + in-cluster Kafka connectivity;
this is what the verified evidence used):

```bash
kubectl exec -n eventflow deploy/order-service -- \
  curl -s -X POST http://order-service:8080/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId": "550e8400-e29b-41d4-a716-446655440000", "amount": 125.50}'

kubectl logs -n eventflow deploy/fulfillment-service | grep FULFILLED
```
