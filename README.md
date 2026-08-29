# Distributed Reliable Task Scheduler

A production-style distributed task scheduling platform built to demonstrate real backend and distributed-systems engineering: durable task acceptance, at-least-once processing, retries with backoff, idempotency, crash recovery, and horizontal scalability.

> **Status**: Under active development. See [Implementation Status](#implementation-status) for what's actually built vs. planned. No performance numbers appear anywhere in this document unless they come from an actual benchmark run — see [`docs/BENCHMARKS.md`](docs/BENCHMARKS.md).

---

## Table of Contents

1. [Problem](#1-problem)
2. [Goals](#2-goals)
3. [Architecture](#3-architecture)
4. [Components](#4-components)
5. [Task Lifecycle](#5-task-lifecycle)
6. [Database Schema](#6-database-schema)
7. [API](#7-api)
8. [Scheduling Algorithm](#8-scheduling-algorithm)
9. [Queue Architecture](#9-queue-architecture)
10. [Worker Architecture](#10-worker-architecture)
11. [Retry Mechanism](#11-retry-mechanism)
12. [Idempotency](#12-idempotency)
13. [Failure Recovery](#13-failure-recovery)
14. [Observability](#14-observability)
15. [Getting Started](#15-getting-started)
16. [Docker Setup](#16-docker-setup)
17. [Kubernetes Deployment](#17-kubernetes-deployment)
18. [Testing](#18-testing)
19. [Load Testing](#19-load-testing)
20. [Performance Results](#20-performance-results)
21. [Design Trade-offs](#21-design-trade-offs)
22. [Limitations](#22-limitations)
23. [Future Improvements](#23-future-improvements)
24. [Implementation Status](#implementation-status)

---

## 1. Problem

Real production workloads — payment processing, email delivery, report generation, webhook callbacks — need to be scheduled and executed reliably, even when individual system components crash. A naive in-memory task runner loses all pending work on restart.

This project builds a system where tasks can be submitted for:

- **Immediate** execution
- **Deferred** execution at a specified future time
- **Recurring** execution on a cron schedule

and the system guarantees that once accepted, a task will eventually reach a terminal state (`SUCCESS`, `DEAD_LETTER`, or `CANCELLED`) — never silently disappear.

### Core invariant

> Once the API returns `201 Created` for a task, the system must not silently lose it.

### On "exactly-once"

This system does **not** claim exactly-once execution. In a distributed system with external side effects (e.g., a payment call), exactly-once is not achievable in the general case — a worker can execute the side effect and crash before recording success, causing a retry. The system instead provides:

```
At-least-once processing + idempotency primitives
```

See [Section 12](#12-idempotency) for how duplicate-safety is actually achieved.

---

## 2. Goals

| Priority | Goal |
|---|---|
| 1 | **Durability** — accepted tasks survive process/container crashes |
| 2 | **Reliability** — transient failures trigger retry, not permanent failure |
| 3 | **Fault tolerance** — one component's failure doesn't take down the system |
| 4 | **Horizontal scalability** — throughput scales by adding workers |
| 5 | **Observability** — task state, failures, and system health are all visible |
| 6 | **Correctness** — no double-dispatch, no lost updates, explicit state machine |

Non-goals: exactly-once execution, task DAGs/dependencies, multi-tenancy (see [Limitations](#22-limitations)).

---

## 3. Architecture

```
Client → API → PostgreSQL (source of truth)
                    ↑
              Scheduler (poll + claim via FOR UPDATE SKIP LOCKED)
                    ↓
                RabbitMQ
                    ↓
         Worker 1 / Worker 2 / Worker N
                    ↓
         Task execution → write outcome back to PostgreSQL
```

**PostgreSQL is the single source of truth.** RabbitMQ is a transport mechanism only — if a message is lost, the task still exists in the database and is recovered by a lease-expiry sweep. This avoids split-brain between queue state and database state.

Full architecture diagrams and the rationale for this design are in [`docs/BLUEPRINT.md`](docs/BLUEPRINT.md).

---

## 4. Components

| Component | Responsibility | Stateless? |
|---|---|---|
| **API** | Validate, persist, and expose task state | Yes |
| **Scheduler** | Poll for due tasks, atomically claim them, dispatch to queue | Yes (coordinates via DB, not leader election) |
| **Worker** | Consume from queue, execute task handler, record outcome | Yes |
| **PostgreSQL** | Durable task metadata, state, and audit trail | No — source of truth |
| **RabbitMQ** | Asynchronous transport between scheduler and workers | No — but not authoritative |
| **Redis** | Rate limiting, optional coordination — never task state | No — ephemeral only |

---

## 5. Task Lifecycle

```
SCHEDULED → QUEUED → RUNNING → SUCCESS
                         ↓
                    RETRY_WAIT → SCHEDULED (loop until max_attempts)
                         ↓
                      FAILED → DEAD_LETTER (manual retry re-enters SCHEDULED)

SCHEDULED / QUEUED → CANCELLED (user-initiated)
```

Every transition is enforced as an atomic compare-and-swap at the database level:

```sql
UPDATE tasks SET status = :newStatus WHERE id = :id AND status = :expectedStatus
```

If the `WHERE` clause matches 0 rows, the transition is rejected — this is how concurrent schedulers/workers avoid double-dispatch without a distributed lock. Full legal/illegal transition table: [`docs/BLUEPRINT.md#7-task-state-machine`](docs/BLUEPRINT.md).

---

## 6. Database Schema

Two tables, managed via Flyway migrations in [`database/migration/`](database/migration/):

- **`tasks`** — current state, scheduling info, retry/lease metadata, idempotency key
- **`task_attempts`** — full audit trail of every execution attempt (for debugging "what happened to this task?")

Key indexes support the scheduler's poll query, the lease-recovery sweep, the retry sweep, and idempotency-key lookups — all as partial indexes scoped to the relevant status, since most tasks are in terminal states at any given time.

Full schema and index rationale: [`docs/BLUEPRINT.md#8-database-schema`](docs/BLUEPRINT.md).

---

## 7. API

Base path: `/api/v1`

| Method | Path | Description |
|---|---|---|
| `POST` | `/tasks` | Create a task |
| `GET` | `/tasks/{id}` | Get task by ID |
| `GET` | `/tasks` | List tasks (filterable, cursor-paginated) |
| `POST` | `/tasks/{id}/cancel` | Cancel a task |
| `POST` | `/tasks/{id}/retry` | *(planned)* Retry a dead-lettered task |
| `DELETE` | `/tasks/{id}` | *(planned)* Soft-delete a task |

Idempotency is supported via an `idempotencyKey` field / `Idempotency-Key` header — a duplicate submission returns the original task with `200 OK` instead of creating a second row.

Full request/response shapes, validation rules, and the cursor-pagination design: [`docs/BLUEPRINT.md#9-api-design`](docs/BLUEPRINT.md). Live OpenAPI docs (once running): `http://localhost:8080/swagger-ui.html`.

---

## 8. Scheduling Algorithm

The scheduler polls PostgreSQL on a fixed interval (default 5s), claiming due tasks with:

```sql
SELECT id FROM tasks
WHERE status = 'SCHEDULED' AND scheduled_at <= now()
ORDER BY priority, scheduled_at
LIMIT 50
FOR UPDATE SKIP LOCKED
```

`FOR UPDATE SKIP LOCKED` is what allows multiple scheduler instances to run concurrently without a distributed lock — each instance simply skips rows another instance already has locked. The task is committed as `QUEUED` in the database **before** being published to RabbitMQ, so a broker failure never leaves a message in the queue for a task the DB doesn't know about — only the reverse (self-healing via the stale-QUEUED recovery sweep).

Rationale for this ordering and for choosing DB-level coordination over Redis locks: [`docs/BLUEPRINT.md#10-scheduler-design`](docs/BLUEPRINT.md).

---

## 9. Queue Architecture

RabbitMQ decouples scheduling from execution — it buffers bursts, lets workers scale independently, and provides consumer-level acknowledgement. Messages carry only a task reference (ID + minimal metadata), never the full payload; the worker always re-fetches the current task state from PostgreSQL before executing, so a task cancelled between dispatch and consumption is never executed.

Details: [`docs/BLUEPRINT.md#11-queue-design`](docs/BLUEPRINT.md).

---

## 10. Worker Architecture

Workers consume messages, acquire an execution lease (`QUEUED → RUNNING` via the same CAS pattern), route to the correct `TaskHandler` by `task_type`, execute with a timeout, and record the outcome. Long-running tasks renew their lease periodically; if a worker crashes without renewing, the lease expires and the task becomes eligible for recovery.

Task types are handled by pluggable `TaskHandler` implementations (`EMAIL`, `HTTP` webhook dispatch, `DEMO`) — the system does not execute arbitrary user code.

Details: [`docs/BLUEPRINT.md#12-worker-design`](docs/BLUEPRINT.md).

---

## 11. Retry Mechanism

Failed executions are retried with exponential backoff and jitter:

```
delay = min(base_delay_ms × 2^(attempt - 1), max_delay_ms) + random(0, jitter_ms)
```

Retries are managed at the application level (via DB state), not RabbitMQ's own redelivery — this gives full control over backoff, per-task attempt counts, and observable retry state. After `max_attempts` is exhausted, the task moves to `DEAD_LETTER` for operator inspection.

Details: [`docs/BLUEPRINT.md#13-retry-architecture`](docs/BLUEPRINT.md).

---

## 12. Idempotency

Two independent layers:

| Layer | Protects against | Mechanism |
|---|---|---|
| **Submission** (API) | Duplicate task creation from client retries | Unique index on `idempotency_key`; duplicate submission returns the existing task |
| **Execution** (Worker) | Duplicate side effects from at-least-once redelivery | Worker checks current task status before executing; skips if already `SUCCESS`/`CANCELLED`. Handler-level idempotency (e.g., passing the key to an external API) depends on the specific task type. |

This system does **not** claim it can make every external side effect idempotent — that depends on whether the downstream operation supports it. What the infrastructure guarantees is that duplicate *delivery* is made *safe*, not that duplicate delivery cannot happen.

---

## 13. Failure Recovery

| Failure | Recovery |
|---|---|
| Worker crash mid-execution | Lease expires → sweep moves task back to `QUEUED`/`RETRY_WAIT` |
| Scheduler crash | Task remains `SCHEDULED`; another instance (or the same one on restart) picks it up |
| RabbitMQ message lost | Task stuck in `QUEUED` past a threshold → stale-QUEUED sweep resets it to `SCHEDULED` |
| Database unavailable | API returns `503`; no false-success responses; no state corruption |

Full failure matrix: [`docs/BLUEPRINT.md#15-failure-recovery`](docs/BLUEPRINT.md).

---

## 14. Observability

*(Planned — see [Implementation Status](#implementation-status))*

- **Metrics**: Micrometer → Prometheus → Grafana (task throughput, queue depth, latency percentiles, retry/failure rates)
- **Logging**: Structured JSON logs with `task_id`, `worker_id`, `attempt`, `event` on every state transition
- **Tracing**: OpenTelemetry spans across API → DB → Scheduler → Queue → Worker, correlated by `task_id`

Full metric/log/trace catalog: [`docs/BLUEPRINT.md#20-observability`](docs/BLUEPRINT.md).

---

## 15. Getting Started

### Prerequisites

- **Java 21** (JDK 21 — not newer; the project targets LTS 21 specifically)
- **Docker** + **Docker Compose**
- **Maven Wrapper** (bundled — no local Maven install needed)

### Build

```bash
./mvnw clean install -DskipTests
```

### Run infrastructure only

```bash
docker compose up -d postgres rabbitmq redis
docker compose ps   # confirm all show "healthy"
```

### Run the API service locally

```bash
./mvnw -pl services/api spring-boot:run
```

### Smoke test

```bash
curl -X POST http://localhost:8080/api/v1/tasks \
  -H "Content-Type: application/json" \
  -d '{"taskType":"DEMO","payload":{"foo":"bar"}}'
```

Expected: `201 Created` with a task ID and `status: "SCHEDULED"`.

---

## 16. Docker Setup

Full stack (once all services are implemented):

```bash
docker compose up
```

Services: `api`, `scheduler`, `worker` (×3 replicas), `postgres`, `rabbitmq`, `redis`, `prometheus`, `grafana`. All infra services have health checks; application services wait on `service_healthy` before starting. See [`docs/BLUEPRINT.md#24-docker-architecture`](docs/BLUEPRINT.md).

---

## 17. Kubernetes Deployment

*(Planned)* Helm chart under `k8s/helm/task-scheduler/`, introduced only after the system is verified working under Docker Compose. Target replica strategy: API ×3 (HPA on CPU), Scheduler ×2 (fixed — `SKIP LOCKED` doesn't benefit from more), Worker ×3–10 (HPA on queue depth). Details: [`docs/BLUEPRINT.md#25-kubernetes-architecture`](docs/BLUEPRINT.md).

---

## 18. Testing

| Level | Tooling | What it covers |
|---|---|---|
| Unit | JUnit 5, Mockito | State machine transitions, retry backoff math, validation rules |
| Integration | Testcontainers (real Postgres/RabbitMQ/Redis) | API↔DB, Scheduler↔DB↔Queue, Worker↔Queue↔DB |
| End-to-end | Awaitility | Full task lifecycle: submit → SUCCESS |
| Failure/chaos | Manual container kill + assertions | Worker crash, scheduler crash, duplicate delivery, broker outage |

```bash
./mvnw -pl shared,services/api test
```

Full testing strategy: [`docs/BLUEPRINT.md#22-testing-strategy`](docs/BLUEPRINT.md).

---

## 19. Load Testing

*(Planned)* k6 scripts under `loadtest/scripts/`, run at four workload tiers (100 / 1,000 / 10,000 / 100,000 tasks). Measures submission throughput, scheduling latency, execution latency, end-to-end latency, and recovery time after a simulated crash.

---

## 20. Performance Results

**Not measured yet.** No throughput or latency numbers will appear here until real benchmarks have been run. Results will be published in [`docs/BENCHMARKS.md`](docs/BENCHMARKS.md) with the exact hardware/replica configuration used to produce them.

---

## 21. Design Trade-offs

| Decision | Trade-off |
|---|---|
| Polling scheduler (not event-driven) | Simpler, but scheduling latency is bounded by poll interval (default 5s) |
| `FOR UPDATE SKIP LOCKED` over Redis locks | No extra infrastructure; correctness backed by DB transactions — but ties coordination to PostgreSQL throughput |
| At-least-once, not exactly-once | Realistic and honest; requires idempotency at the handler level |
| Single scheduler is simpler but a SPOF | Solved by running multiple instances — no leader election needed |
| Commit-then-publish (not publish-then-commit) | A lost RabbitMQ publish is self-healing via recovery sweep; the reverse ordering is not |

Full discussion: [`docs/BLUEPRINT.md#28-technology-choices-with-alternatives-and-trade-offs`](docs/BLUEPRINT.md).

---

## 22. Limitations

- **Not exactly-once** — a fundamental constraint with external side effects, not an oversight
- **Scheduling latency is bounded by poll interval** — not sub-second by design
- **PostgreSQL is the throughput ceiling** — sufficient for the target scale (1K–100K tasks/day); would need sharding beyond that
- **No task ordering guarantee** — concurrent workers mean execution order is approximate, not strict
- **No task dependencies/DAGs** — out of scope for this phase
- **Single-tenant** — no namespace/tenant isolation
- **Basic auth only** — API key, not OAuth2/JWT

Full list: [`docs/BLUEPRINT.md#30-known-limitations`](docs/BLUEPRINT.md).

---

## 23. Future Improvements

- Task DAGs / dependencies between tasks
- PostgreSQL `LISTEN/NOTIFY` for sub-second dispatch latency on immediate tasks
- Citus-based partitioning for scale beyond a single PostgreSQL node
- OAuth2/JWT authentication
- Admin UI for task inspection and manual retry

Full list with complexity estimates: [`docs/BLUEPRINT.md#31-future-improvements`](docs/BLUEPRINT.md).

---

## Implementation Status

This section is kept up to date manually as milestones land — it reflects what's actually built, not what's planned.

| Phase | Status |
|---|---|
| 1 — Blueprint | ✅ Done |
| 2 — Foundation (multi-module, Docker Compose, migrations) | ✅ Done |
| 2b — Shared domain (entity, enums, state machine) | ✅ Done |
| 3 — API service | 🚧 In progress |
| 4 — State machine integration in API | 🚧 In progress (part of Phase 3) |
| 5 — Scheduler | ⏳ Not started |
| 6 — Worker | ⏳ Not started |
| 7 — Retry & recovery sweeps | ⏳ Not started |
| 8 — Recurring tasks, webhook handler | ⏳ Not started |
| 9 — Observability (metrics/logs/tracing) | ⏳ Not started |
| 10 — Security & hardening | ⏳ Not started |
| 11 — CI/CD | ⏳ Not started |
| 12 — Load testing | ⏳ Not started |
| 13 — Kubernetes | ⏳ Not started |
| 14 — Final docs & polish | ⏳ Not started |

---

## Project Structure

```
distributed-task-scheduler/
├── services/{api,scheduler,worker}/   # Spring Boot services
├── shared/                             # Task entity, state machine, repositories
├── database/migration/                 # Flyway SQL migrations
├── monitoring/                         # Prometheus/Grafana config (planned)
├── k8s/                                 # K8s manifests + Helm chart (planned)
├── loadtest/                            # k6 scripts (planned)
├── docs/BLUEPRINT.md                    # Full engineering blueprint
├── docs/BENCHMARKS.md                   # Benchmark results (post-testing)
├── docker-compose.yml
└── README.md                            # This file
```

## Further Reading

- [`docs/BLUEPRINT.md`](docs/BLUEPRINT.md) — the full engineering blueprint (architecture, schema, every design decision with alternatives and trade-offs, expected interview questions)
- [`PROJECT_CONTEXT.md`](PROJECT_CONTEXT.md) — original project goals and constraints
- [`TECH_STACK.md`](TECH_STACK.md) — technology selection rationale
