# Distributed Reliable Task Scheduler

A production-style distributed task scheduling platform demonstrating backend and distributed-systems engineering: durable task acceptance, at-least-once processing, retries with backoff, idempotency, crash recovery, observability, and horizontal scalability.

Built with Java 21, Spring Boot, PostgreSQL, RabbitMQ, and Redis.

The core system, observability stack, security hardening, and load testing are implemented and verified. See [Implementation Status](#implementation-status) for the exact state of every phase. No performance figure appears in this document unless it comes from an actual benchmark run; see `docs/BENCHMARKS.md`.

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
15. [Security](#15-security)
16. [Getting Started](#16-getting-started)
17. [Docker Setup](#17-docker-setup)
18. [Kubernetes Deployment](#18-kubernetes-deployment)
19. [Testing](#19-testing)
20. [Load Testing](#20-load-testing)
21. [Performance Results](#21-performance-results)
22. [Design Trade-offs](#22-design-trade-offs)
23. [Limitations](#23-limitations)
24. [Future Improvements](#24-future-improvements)
25. [Implementation Status](#implementation-status)

---

## 1. Problem

Production workloads such as payment processing, email delivery, report generation, and webhook callbacks need to be scheduled and executed reliably, even when individual system components fail. A naive in-memory task runner loses all pending work on restart.

This project builds a system where tasks can be submitted for:

- Immediate execution
- Deferred execution at a specified future time
- Recurring execution on a cron schedule

Once accepted, a task is guaranteed to eventually reach a terminal state (`SUCCESS`, `DEAD_LETTER`, or `CANCELLED`); it never silently disappears.

### Core invariant

Once the API returns `201 Created` for a task, the system must not silently lose it.

### On exactly-once execution

This system does not claim exactly-once execution. In a distributed system with external side effects, such as a payment call, exactly-once is not achievable in the general case: a worker can execute the side effect and then crash before recording success, which triggers a retry. The system instead provides at-least-once processing combined with idempotency primitives. See [Section 12](#12-idempotency) for how duplicate-safety is actually achieved.

---

## 2. Goals

| Priority | Goal |
|---|---|
| 1 | Durability: accepted tasks survive process and container crashes |
| 2 | Reliability: transient failures trigger retry, not permanent failure |
| 3 | Fault tolerance: one component's failure does not take down the system |
| 4 | Horizontal scalability: throughput scales by adding workers |
| 5 | Observability: task state, failures, and system health are all visible |
| 6 | Correctness: no double-dispatch, no lost updates, explicit state machine |

Non-goals: exactly-once execution, task DAGs and dependencies, multi-tenancy. See [Limitations](#23-limitations).

---

## 3. Architecture

```
Client -> API -> PostgreSQL (source of truth)
                     ^
               Scheduler (poll and claim via FOR UPDATE SKIP LOCKED)
                     |
                 RabbitMQ
                     |
          Worker 1 / Worker 2 / Worker N
                     |
          Task execution -> outcome written back to PostgreSQL

Observability: Micrometer/Prometheus/Grafana, structured JSON logs, and
OpenTelemetry/Jaeger tracing, all correlated by task_id.
```

PostgreSQL is the single source of truth. RabbitMQ is a transport mechanism only: if a message is lost, the task still exists in the database and is recovered by a lease-expiry sweep. This avoids a split-brain between queue state and database state.

Full architecture diagrams and design rationale are in `docs/BLUEPRINT.md`.

---

## 4. Components

| Component | Responsibility | Stateless |
|---|---|---|
| API | Validate, persist, authenticate, rate-limit, and expose task state | Yes |
| Scheduler | Poll for due tasks, atomically claim them, dispatch to queue, run recovery sweeps | Yes; coordinates via the database, not leader election |
| Worker | Consume from queue, execute the task handler, record the outcome, emit traces/metrics/logs | Yes |
| PostgreSQL | Durable task metadata, state, and audit trail | No; source of truth |
| RabbitMQ | Asynchronous transport between scheduler and workers | No; not authoritative |
| Redis | Rate limiting only, never task state | No; ephemeral |
| Prometheus / Grafana | Metrics collection and dashboards | No |
| Jaeger | Distributed trace collection and visualization | No |

---

## 5. Task Lifecycle

```
SCHEDULED -> QUEUED -> RUNNING -> SUCCESS
                          |
                     RETRY_WAIT -> SCHEDULED (loops until max_attempts)
                          |
                       FAILED -> DEAD_LETTER (manual retry re-enters SCHEDULED)

SCHEDULED / QUEUED -> CANCELLED (user-initiated)
RUNNING -> QUEUED (lease-expiry recovery, e.g. worker crash)
QUEUED -> SCHEDULED (stale-QUEUED recovery, e.g. lost RabbitMQ message)
```

Every transition is enforced as an atomic compare-and-swap at the database level:

```sql
UPDATE tasks SET status = :newStatus WHERE id = :id AND status = :expectedStatus
```

If the `WHERE` clause matches zero rows, the transition is rejected. This is how concurrent schedulers and workers avoid double-dispatch without a distributed lock. It has been verified with a concurrency test simulating two scheduler instances claiming tasks at the same time (zero overlap, complete coverage), and with an end-to-end test simulating a worker crash mid-execution, where a different worker instance recovers and completes the task.

The full legal and illegal transition table is in `docs/BLUEPRINT.md`, Section 7.

---

## 6. Database Schema

Two core tables, plus soft-delete and tracing extensions, managed with Flyway migrations under `shared/src/main/resources/db/migration/`:

- `tasks`: current state, scheduling info, retry and lease metadata, idempotency key, soft-delete marker (`deleted_at`), and `traceparent` for linking traces across an asynchronous boundary
- `task_attempts`: a full audit trail of every execution attempt

Key indexes support the scheduler's poll query, the lease-recovery sweep, the retry sweep, and idempotency-key lookups, all as partial indexes scoped to the relevant status.

Full schema and index rationale: `docs/BLUEPRINT.md`, Section 8.

---

## 7. API

Base path: `/api/v1`. All endpoints except `/actuator/health`, `/actuator/prometheus`, and Swagger UI require an `X-API-Key` header. See [Security](#15-security).

| Method | Path | Description |
|---|---|---|
| POST | `/tasks` | Create a task |
| GET | `/tasks/{id}` | Get a task by ID |
| GET | `/tasks` | List tasks, filterable and cursor-paginated |
| POST | `/tasks/{id}/cancel` | Cancel a task |
| POST | `/tasks/{id}/retry` | Retry a dead-lettered task |
| DELETE | `/tasks/{id}` | Soft-delete a task |

Idempotency is supported through an `idempotencyKey` field or an `Idempotency-Key` header. A duplicate submission returns the original task with `200 OK` instead of creating a second row.

Full request and response shapes, validation rules, and the cursor-pagination design are in `docs/BLUEPRINT.md`, Section 9. Live OpenAPI documentation is available at `http://localhost:8080/swagger-ui.html` once the API is running.

---

## 8. Scheduling Algorithm

The scheduler polls PostgreSQL on a fixed interval, five seconds by default, claiming due tasks with:

```sql
SELECT id FROM tasks
WHERE status = 'SCHEDULED' AND scheduled_at <= now() AND deleted_at IS NULL
ORDER BY priority, scheduled_at
LIMIT 50
FOR UPDATE SKIP LOCKED
```

`FOR UPDATE SKIP LOCKED` allows multiple scheduler instances to run concurrently without a distributed lock. The task is committed as `QUEUED` before being published to RabbitMQ, a commit-then-publish ordering, so a broker failure never leaves a message in the queue for a task the database does not know about.

Two background recovery sweeps run alongside the main poll loop:

- Lease-expiry sweep: recovers tasks stuck in `RUNNING` past their lease, for example after a worker crash
- Stale-QUEUED sweep: recovers tasks stuck in `QUEUED` with no corresponding message, for example after a lost RabbitMQ publish

Full rationale: `docs/BLUEPRINT.md`, Section 10.

---

## 9. Queue Architecture

RabbitMQ decouples scheduling from execution. It buffers bursts, lets workers scale independently, and provides consumer-level acknowledgement. Messages carry only a task reference, never the full payload; the worker always re-fetches the current task state from PostgreSQL before executing, so a task cancelled between dispatch and consumption is never run.

Full details: `docs/BLUEPRINT.md`, Section 11.

---

## 10. Worker Architecture

Workers consume messages, acquire an execution lease through the same compare-and-swap pattern (`QUEUED` to `RUNNING`), route to the correct task handler by `task_type`, execute with a timeout on a virtual-thread executor, and record the outcome. Graceful shutdown drains in-flight tasks before the process terminates.

Implemented handlers:

- `DemoTaskHandler`: a no-op success handler used for testing
- `EmailTaskHandler`: a simulated email dispatch with idempotency-key forwarding; no real email provider is wired in
- `HttpTaskHandler`: a real webhook dispatch using `HttpClient`, with configurable connect and read timeouts, treating any non-2xx response as a failure

Full details: `docs/BLUEPRINT.md`, Section 12.

---

## 11. Retry Mechanism

Failed executions are retried with exponential backoff and jitter:

```
delay = min(base_delay_ms * 2^(attempt - 1), max_delay_ms) + random(0, jitter_ms)
```

Retries are managed at the application level through database state, not through RabbitMQ redelivery. After `max_attempts` is exhausted, the task moves to `DEAD_LETTER` and can be manually re-entered into the lifecycle with `POST /tasks/{id}/retry`.

Full details: `docs/BLUEPRINT.md`, Section 13.

---

## 12. Idempotency

| Layer | Protects against | Mechanism |
|---|---|---|
| Submission (API) | Duplicate task creation from client retries | A unique index on `idempotency_key`; a duplicate submission returns the existing task |
| Execution (Worker) | Duplicate side effects from at-least-once redelivery | The worker checks the current task status before executing and skips work if the task is already `SUCCESS` or `CANCELLED` |

This has been verified with an integration test proving that a redelivered message for an already-successful task does not re-invoke the handler.

---

## 13. Failure Recovery

| Failure | Recovery | Verified |
|---|---|---|
| Worker crash mid-execution | Lease expires; the sweep moves the task back to `QUEUED`; it is re-dispatched and a different worker completes it | Yes, end-to-end test |
| Scheduler crash | Task remains `SCHEDULED`; another instance picks it up | Yes |
| RabbitMQ message lost | The stale-QUEUED sweep resets the task to `SCHEDULED` | Yes |
| Database unavailable | API returns `503`; no false-success responses | Documented; not chaos-tested |

Full failure matrix: `docs/BLUEPRINT.md`, Section 15.

---

## 14. Observability

Fully implemented across all three services.

Metrics use Micrometer, exported to Prometheus and visualized in Grafana. Custom instrumentation includes counters such as `tasks_submitted_total`, `tasks_completed_total`, `tasks_retried_total`, `scheduler_claims_total`, and `lease_recoveries_total`; gauges such as `queue_depth` per priority queue, `active_workers`, and `tasks_in_status`; and histograms such as `task_execution_duration_seconds` and `task_scheduling_latency_seconds`. A provisioned dashboard, "Task Scheduler Overview," is available at `http://localhost:3000` with credentials `admin` / `admin`.

Structured logging uses Logback with the `logstash-logback-encoder`, producing JSON log lines that include `task_id`, `worker_id`, `attempt`, `event`, `traceId`, and `spanId` on every key transition. This has been verified to propagate correctly even inside task handlers executed on virtual threads.

Distributed tracing uses OpenTelemetry, with spans exported to Jaeger at `http://localhost:16686`, covering the path from API through Scheduler to Worker. Because the scheduler's dispatch happens asynchronously, by polling a database row rather than through a direct call, the `traceparent` value is persisted on the task row at creation time and re-hydrated by the scheduler to correctly link the trace across that asynchronous boundary. This does not happen automatically with standard instrumentation and required explicit context propagation to work correctly.

All three observability pillars are correlated by `task_id`, and traces and logs additionally share `traceId` and `spanId`.

Full metric, log, and trace catalog: `docs/BLUEPRINT.md`, Section 20.

---

## 15. Security

- API key authentication: an `X-API-Key` header is required on all endpoints except health, metrics, and API documentation. A missing or invalid key returns `401`. There is no default fallback key; the service fails to start if `API_KEY` is not configured.
- Rate limiting: a Redis-backed sliding window, 100 requests per minute per API key by default, with independent buckets per key rather than a single global limit. The limiter fails open if Redis is unavailable, so rate limiting can never become a hard dependency for task submission.
- Input validation: a task-type whitelist, a 64 KB payload limit, cron expression validation, bounds on `scheduled_at`, and length limits on key string fields.
- Secrets: no credentials are hardcoded anywhere; all secrets are supplied through environment variables, documented in `.env.example`.

Full rationale: `docs/BLUEPRINT.md`, Section 21.

---

## 16. Getting Started

### Prerequisites

- Java 21 (JDK 21 specifically; this project targets that LTS release)
- Docker and Docker Compose
- The bundled Maven Wrapper (no local Maven installation required)

### Build

```bash
./mvnw clean install -DskipTests
```

### Run the full stack

```bash
docker compose up -d --build
docker compose ps
```

Confirm all eight services show as healthy or up.

### Smoke test

```bash
curl -X POST http://localhost:8080/api/v1/tasks \
  -H "X-API-Key: <your-configured-key>" \
  -H "Content-Type: application/json" \
  -d '{"taskType":"DEMO","payload":{"foo":"bar"}}'
```

Expected result: `201 Created`, with a task ID and status `SCHEDULED`.

An `API_KEY` value must be set before the service will start; see `.env.example`.

---

## 17. Docker Setup

```bash
docker compose up -d --build
```

Services: `api`, `scheduler`, `worker`, `postgres`, `rabbitmq`, `redis`, `prometheus`, `grafana`, and `jaeger`. All application services are containerized and communicate over the Docker Compose network using service names, not `host.docker.internal`.

Full details: `docs/BLUEPRINT.md`, Section 24.

---

## 18. Kubernetes Deployment

Not yet implemented. The target replica strategy is documented in the blueprint: API at three replicas with CPU-based autoscaling, Scheduler at two fixed replicas, and Worker at three to ten replicas with queue-depth-based autoscaling.

Full details: `docs/BLUEPRINT.md`, Section 25.

---

## 19. Testing

| Level | Tooling | Coverage |
|---|---|---|
| Unit | JUnit 5, Mockito | State machine transitions, retry backoff math, cron calculation, validation rules |
| Integration | Testcontainers, real PostgreSQL/RabbitMQ/Redis | API-to-database, scheduler-to-database-to-queue, worker-to-queue-to-database, security filters |
| End-to-end | Direct sweep and poll invocation, no sleep-based waits | Full task lifecycle, worker crash recovery, recurring task chaining |
| Failure and chaos | Simulated lease expiry, simulated Redis unavailability | Crash recovery, fail-open rate limiting |

```bash
./mvnw clean test
```

71 of 71 tests pass across all four modules as of the last full run. Full testing strategy: `docs/BLUEPRINT.md`, Section 22.

---

## 20. Load Testing

k6 scripts live under `loadtest/scripts/`:

- `submission-throughput.js`: ramping virtual-user load against `POST /tasks`
- `end-to-end-latency.js`: submit-then-poll-to-success latency measurement
- `burst-handling.js`: configurable burst submission, tested at 1,000 and 10,000 task tiers
- `scale-out.md`: a documented manual procedure for multi-replica worker throughput testing

Run any script with `k6 run loadtest/scripts/<script>.js` against the live Docker Compose stack.

Not yet run: the 100,000-task tier, and multi-replica scale-out, which is currently blocked by a static `container_name` in `docker-compose.yml` that needs to be removed before `docker compose up --scale worker=N` will work.

Full raw results: `docs/BENCHMARKS.md`.

---

## 21. Performance Results

These are real, measured results from actual k6 runs against the live stack. See `docs/BENCHMARKS.md` for full raw output, exact configuration, and the date of measurement.

- The API accepts over 1,100 tasks per second at burst, with a 0 percent failure rate and sub-100ms p95 latency.
- End-to-end latency averages approximately five seconds, directly bounded by the scheduler's five-second poll interval. This confirms, empirically rather than only theoretically, the design trade-off documented in Section 22.
- A single scheduler instance drains approximately 10 tasks per second at default configuration (batch size 50, poll interval 5 seconds), far below the API's ingestion rate. Excess load buffers safely in PostgreSQL in `SCHEDULED` status rather than being lost. Closing this gap requires tuning the batch size or poll interval, or running multiple scheduler instances, which has already been proven safe by the concurrency test.
- Zero task loss was observed across more than 30,000 total submissions during testing.

---

## 22. Design Trade-offs

| Decision | Trade-off |
|---|---|
| A polling scheduler rather than an event-driven one | Simpler, but scheduling latency is bounded by the poll interval; confirmed at approximately five seconds average under load |
| `FOR UPDATE SKIP LOCKED` rather than Redis-based locks | No additional infrastructure; correctness is backed by database transactions |
| At-least-once rather than exactly-once processing | A realistic and honest guarantee; requires idempotency at the handler level |
| Commit-then-publish rather than publish-then-commit | A lost RabbitMQ publish is self-healing through the stale-QUEUED sweep |
| A simple request filter rather than full Spring Security | Full role-based access control is explicitly out of scope; a simpler filter chain is sufficient for API-key authentication and rate limiting |
| Fail-open rate limiting | Redis unavailability never blocks task submission, matching its documented role as non-authoritative infrastructure |

Full discussion: `docs/BLUEPRINT.md`, Section 28.

---

## 23. Limitations

- Not exactly-once. This is a fundamental constraint of systems with external side effects, not an oversight.
- Scheduling latency is bounded by the poll interval, by design, and this has been confirmed under load.
- A single scheduler instance drains slower than the API can ingest at default configuration. This is a tuning and scaling question, not a correctness issue; durability is preserved either way.
- No strict task ordering guarantee; concurrent workers mean execution order is approximate.
- No task dependencies or DAGs.
- Single-tenant; there is no namespace or tenant isolation.
- API key authentication only; no OAuth2 or JWT support.
- The 100,000-task load tier and multi-replica scale-out have not yet been measured.

Full list: `docs/BLUEPRINT.md`, Section 30.

---

## 24. Future Improvements

- Task DAGs and dependencies between tasks
- PostgreSQL `LISTEN`/`NOTIFY` for sub-second dispatch latency
- Citus-based partitioning to scale beyond a single PostgreSQL node
- OAuth2 or JWT authentication
- An administrative UI for task inspection and manual retry
- A GitHub Actions CI/CD pipeline
- Kubernetes and Helm deployment

Full list with complexity estimates: `docs/BLUEPRINT.md`, Section 31.

---

## Implementation Status

This section is kept up to date manually as milestones land. It reflects what is actually built and verified, not what is merely planned.

| Phase | Status |
|---|---|
| 1: Blueprint | Done |
| 2: Foundation | Done, verified |
| 2b: Shared domain (entity, state machine) | Done, verified |
| 3/4: API service | Done, verified |
| 5: Scheduler | Done, verified, including the concurrency proof |
| 6: Worker | Done, verified, including the idempotency proof and graceful shutdown |
| 7: Retry and recovery sweeps | Done, verified, including end-to-end crash recovery |
| 8: Recurring tasks, Email/HTTP handlers, retry and delete endpoints | Done, verified |
| 9: Observability (metrics, logs, dashboards, tracing) | Done, verified |
| 10: Security and hardening | Done, verified |
| 11: CI/CD pipeline | Not started; Dockerfiles for all three services already exist, built during Phase 9 |
| 12: Load testing | Done; 100, 1,000, and 10,000-task tiers measured; the 100,000-task tier and multi-replica scale-out have not yet been run |
| 13: Kubernetes | Not started |
| 14: Final documentation and polish | In progress |

71 of 71 automated tests currently pass.

---

## Project Structure

```
distributed-task-scheduler/
  services/
    api/         Spring Boot service, with its own Dockerfile
    scheduler/   Spring Boot service, with its own Dockerfile
    worker/      Spring Boot service, with its own Dockerfile
  shared/        Task entity, state machine, repositories, metrics
  monitoring/    Prometheus scrape config and Grafana provisioning
  loadtest/      k6 scripts and the manual scale-out procedure
  k8s/           Kubernetes manifests and Helm chart (planned)
  docs/
    BLUEPRINT.md    Full engineering blueprint
    BENCHMARKS.md   Real load test results
  docker-compose.yml   Full eight-service stack
  README.md            This file
```

## Further Reading

- `docs/BLUEPRINT.md`: the full engineering blueprint, covering architecture, schema, and every design decision with alternatives and trade-offs
- `docs/BENCHMARKS.md`: real load test results
- `PROJECT_CONTEXT.md`: original project goals and constraints
- `TECH_STACK.md`: technology selection rationale