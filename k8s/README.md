# K8s + KEDA Deployment

Manifests for running strategy-service and codegen-service as auto-scaling K8s Deployments driven by Kafka consumer-group lag.

## Prerequisites

```bash
# KEDA
helm repo add kedacore https://kedacore.github.io/charts
helm install keda kedacore/keda --namespace keda --create-namespace

# Namespace
kubectl create namespace qa-isystem

# GHCR pull secret
kubectl create secret docker-registry ghcr-pull-secret \
  --docker-server=ghcr.io \
  --docker-username=<your-github-username> \
  --docker-password=<ghcr-token> \
  -n qa-isystem

# App secrets
kubectl create secret generic qa-isystem-env \
  --from-literal=TARGET_REPO_URL=https://github.com/your-org/your-test-repo \
  --from-literal=TARGET_REPO_TOKEN=ghp_xxx \
  --from-literal=TARGET_REPO_USERNAME=your-username \
  --from-literal=GITHUB_WEBHOOK_SECRET=your-webhook-secret \
  --from-literal=AIQA_ADMIN_KEY=$(openssl rand -hex 32) \
  -n qa-isystem

# Kafka bootstrap secret (for KEDA TriggerAuthentication)
kubectl create secret generic kafka-connection \
  --from-literal=bootstrapServers=<kafka-service>:9092 \
  -n qa-isystem
```

## Apply

```bash
# Deployments
kubectl apply -f k8s/strategy-deployment.yaml -n qa-isystem
kubectl apply -f k8s/codegen-deployment.yaml  -n qa-isystem

# KEDA autoscalers
kubectl apply -f k8s/keda/kafka-trigger-auth.yaml    -n qa-isystem
kubectl apply -f k8s/keda/strategy-scaledobject.yaml -n qa-isystem
kubectl apply -f k8s/keda/codegen-scaledobject.yaml  -n qa-isystem
```

## Scaling behaviour

| Service | Kafka topic | Consumer group | Partitions | Max replicas |
|---------|-------------|---------------|-----------|-------------|
| strategy-service | ImpactResultsQueue | strategy-service-group | 12 | 12 |
| codegen-service | TestScriptsQueue | codegen-service-group | 24 | 24 |

- `lagThreshold: 5` → KEDA adds a replica when pending messages per replica > 5
- `AIQA_AGENT_MAX_CONCURRENT: 3` (default) → each replica runs 3 parallel copilot agents
- `max.poll.records=1` + `max.poll.interval.ms=900000` → no rebalance storms from long agent runs

### Throughput at max scale

| Service | Replicas | Agents/replica | Latency | Throughput |
|---------|---------|---------------|---------|-----------|
| strategy | 12 | 3 | ~3.5 min/PR | ~10 PRs/min |
| codegen | 24 | 3 | ~1.5 min/scenario | ~48 scenarios/min |

## Local horizontal scale (docker-compose)

```bash
# Scale codegen to 3 instances (requires 3×AIQA_AGENT_MAX_CONCURRENT unique partitions assigned)
docker compose -f docker-compose.prod.yml up -d --scale codegen-service=3

# Or set AIQA_AGENT_MAX_CONCURRENT higher on a single instance (vertical scale)
AIQA_AGENT_MAX_CONCURRENT=6 docker compose -f docker-compose.prod.yml up -d codegen-service
```

## Monitoring

Both services expose `/actuator/prometheus`. Pods are annotated for Prometheus scraping:
```
prometheus.io/scrape: "true"
prometheus.io/path: /actuator/prometheus
```

Key metrics:
- `aiqa.agent.slots.available` / `aiqa.agent.slots.max` — per-instance concurrency
- `aiqa.agent.workspace.available` — free isolated worktrees
- `aiqa.agent.latency` (histogram) — wall-clock duration of each agent run
