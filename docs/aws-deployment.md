# AWS Deployment (Documented Workflow — No Live Deploy)

> **Where to find what:** this page documents the classic **EC2 + Docker Compose**
> workflow, which remains **documented only** (no AWS account exists). The
> **serverless AWS path** (SQS → Lambda → DynamoDB + S3 via Terraform) is a separate,
> **executed** variant of EventFlow — validated locally against LocalStack with
> captured output; see [aws/README.md](../aws/README.md) and
> [docs/local-aws-validation.md](local-aws-validation.md). Both pages make the same
> boundary explicit: nothing in this repository has ever touched real AWS.

> **Honest status:** the AWS portion of this project is **documented, not executed**.
> Creating an AWS account requires a payment method (credit card) at sign-up, which is
> not available for this project — so no instance was ever launched, and nothing here
> pretends otherwise. The workflow below is written so it can be executed verbatim by
> anyone with an account. Every other part of this repo (build, tests, Docker,
> end-to-end Kafka run) is verified with captured output in [evidence.md](evidence.md).

## Target architecture

A single EC2 instance running the full Docker Compose stack. Compose binds the service
ports to loopback by default; access them through an SSH tunnel. Public port exposure
is an optional, explicitly guarded change described below:

```
Laptop ── SSH tunnel ──► EC2 loopback :8080 (order-service)
                         EC2 Docker Compose: order-service, fulfillment-service, Kafka
                         Kafka + consumer stay inside the Docker network
```

## Route A — single EC2 instance (simplest)

### 1. Launch

- AMI: Amazon Linux 2023 (or Ubuntu 24.04)
- Instance type: **t3.small** recommended — the Spring Boot services plus KRaft Kafka
  want more than t2.micro's 1 GiB; t3.micro works with tight heaps
- Key pair + security group: SSH (22) from *your IP only*. The default SSH-tunnel
  workflow needs no inbound application port. If you opt into direct access, allow
  TCP 8080 from *your IP only* and do not expose the unauthenticated admin port 8081.

```bash
aws ec2 run-instances \
  --image-id <ami-id> \
  --instance-type t3.small \
  --key-name <key-pair> \
  --security-group-ids <sg-id> \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=eventflow}]'
```

### 2. Install Docker and run

```bash
ssh -i <key>.pem ec2-user@<instance-ip>

# Amazon Linux 2023:
sudo dnf install -y docker
sudo systemctl enable --now docker
sudo usermod -aG docker ec2-user   # re-login afterwards
sudo curl -SL https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64 \
  -o /usr/local/lib/docker/cli-plugins/docker-compose && sudo chmod +x $_

# Option 1 — build on the box from a git clone:
sudo dnf install -y git && git clone <repo-url> && cd EventFlow
docker compose up --build -d

# Option 2 — ship prebuilt images:
#   (local)  docker build -t eventflow/order-service:latest -f order-service/Dockerfile .
#   (local)  docker build -t eventflow/fulfillment-service:latest -f fulfillment-service/Dockerfile .
#   (local)  docker save eventflow/order-service:latest eventflow/fulfillment-service:latest | gzip > images.tar.gz
#   (local)  scp -i <key>.pem images.tar.gz docker-compose.yml ec2-user@<ip>:~/
#   (ec2)    docker load < images.tar.gz && docker compose up -d
```

### 3. Smoke test

The compose file publishes ports on **`127.0.0.1` only** (deliberate — the fulfillment
`/__admin` API is unauthenticated and must not be internet-reachable), so test over an
SSH tunnel from your laptop:

```bash
# forward local ports to the instance's loopback:
ssh -i <key>.pem -N \
  -L 8080:127.0.0.1:8080 -L 8081:127.0.0.1:8081 ec2-user@<instance-ip> &

curl -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId": "550e8400-e29b-41d4-a716-446655440000", "amount": 125.50}'
curl http://localhost:8081/__admin/stats
```

If you specifically want the public `http://<instance-ip>:8080/orders` demonstration,
temporarily edit `docker-compose.yml` (`"127.0.0.1:8080:8080"` → `"8080:8080"`),
`docker compose up -d`, run the demo — then **revert it**. Do not leave an
unauthenticated API exposed to the internet.

### 4. Teardown (do not skip)

```bash
docker compose down -v
exit
aws ec2 terminate-instances --instance-ids <instance-id>
# EBS volumes are deleted with the instance when delete-on-termination is set (default)
```

**Cost discipline:** a stopped instance still bills EBS storage; a terminated one does
not. Verify with `aws ec2 describe-instances --filters Name=instance-state-name,Values=running`.

## Route B — persistent volume + systemd (production-shaped, optional)

The above is stateless-by-accident (in-memory stores mean a restart wipes orders — the
documented scope decision). A production-shaped variant would mount an EBS volume for
Kafka's data dir, run compose via a systemd unit so the stack survives reboots, and put
the instance behind an Application Load Balancer with TLS — at which point you are one
step from ECS/EKS territory and should decide consciously whether the extra moving
parts serve the goal.

## Cost notes

- AWS Free Tier terms depend on when the account was created; t3.small is **not**
  Free-Tier-safe, t2/t3.micro is — budget ~2 GB RAM for this stack on micro types
- Always terminate; never just stop
- https://aws.amazon.com/free/
