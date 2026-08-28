# ENGINEERING BLUEPRINT — DISTRIBUTED RELIABLE TASK SCHEDULER

> **Status**: Draft — awaiting review before implementation begins.
>
> **Rule**: No implementation code is written until this blueprint is approved.
>
> **Performance numbers**: None are stated. All performance claims will come from actual benchmarks after implementation.

---

## Table of Contents

1. [Final Problem Statement](#1-final-problem-statement)
2. [Functional Requirements](#2-functional-requirements)
3. [Non-Functional Requirements](#3-non-functional-requirements)
4. [System Architecture](#4-system-architecture)
5. [Component Responsibilities](#5-component-responsibilities)
6. [Complete Task Lifecycle](#6-complete-task-lifecycle)
7. [Task State Machine](#7-task-state-machine)
8. [Database Schema](#8-database-schema)
9. [API Design](#9-api-design)
10. [Scheduler Design](#10-scheduler-design)
11. [Queue Design](#11-queue-design)
12. [Worker Design](#12-worker-design)
13. [Retry Architecture](#13-retry-architecture)
14. [Idempotency Architecture](#14-idempotency-architecture)
15. [Failure Recovery](#15-failure-recovery)
16. [Multi-Scheduler Coordination](#16-multi-scheduler-coordination)
17. [Concurrency Strategy](#17-concurrency-strategy)
18. [Consistency Model](#18-consistency-model)
19. [Reliability Guarantees](#19-reliability-guarantees)
20. [Observability](#20-observability)
21. [Security](#21-security)
22. [Testing Strategy](#22-testing-strategy)
23. [Load-Testing Strategy](#23-load-testing-strategy)
24. [Docker Architecture](#24-docker-architecture)
25. [Kubernetes Architecture](#25-kubernetes-architecture)
26. [Project Folder Structure](#26-project-folder-structure)
27. [Development Roadmap](#27-development-roadmap)
28. [Technology Choices with Alternatives and Trade-offs](#28-technology-choices-with-alternatives-and-trade-offs)
29. [Expected Interview Questions](#29-expected-interview-questions)
30. [Known Limitations](#30-known-limitations)
31. [Future Improvements](#31-future-improvements)

---

## 1. Final Problem Statement

### What

Build a **distributed task scheduling platform** where users or services can submit tasks for:

- **Immediate** execution
- **Deferred** execution at a specified future time
- **Recurring** execution on a cron schedule

The system persists every accepted task and guarantees that it will eventually be executed by a worker, even when individual components (schedulers, workers, queues, databases) experience failures.

### Why

A naive single-process, in-memory task runner loses all pending work on crash. Real production workloads — payment processing, email delivery, report generation, webhook callbacks — require a system that:

- Survives process/container crashes
- Recovers stuck work automatically
- Scales horizontally by adding workers
- Provides visibility into task state and system health
- Handles transient failures via retries with backoff

### Core invariant

> Once the API returns **201 Created** for a task, the system must not silently lose that task. The task must eventually reach a terminal state (`SUCCESS`, `DEAD_LETTER`, or `CANCELLED`).

### Delivery semantics — the critical design decision

This system provides **at-least-once** task processing, **not** exactly-once.

In a distributed system with external side effects, exactly-once execution is impossible to guarantee in the general case. Consider:

```
Worker executes payment → payment succeeds → worker crashes before recording SUCCESS
→ system retries → payment executes again
```

Therefore:

| Layer | Guarantee |
|---|---|
| **Task delivery/processing** | At-least-once |
| **Application-level duplicate protection** | Idempotency keys |

The system makes duplicate delivery **safe** through idempotency, but does **not** claim it can prevent duplicate delivery from ever occurring.

---

## 2. Functional Requirements

### Task Management

| ID | Requirement |
|---|---|
| FR-01 | Submit a one-shot task for immediate or deferred execution |
| FR-02 | Submit a recurring task with a cron expression |
| FR-03 | Query task status by ID |
| FR-04 | List tasks with filtering (status, type, priority) and cursor-based pagination |
| FR-05 | Cancel a task that has not yet completed |
| FR-06 | Manually retry a dead-lettered task |
| FR-07 | Delete a task (soft-delete; the record is retained for audit) |

### Task Execution

| ID | Requirement |
|---|---|
| FR-08 | Route tasks to the correct handler by `task_type` (EMAIL, HTTP, DEMO) |
| FR-09 | Support both internal Java handlers and external HTTP webhook dispatch |
| FR-10 | Enforce per-task execution timeouts |
| FR-11 | Retry failed tasks with exponential backoff + jitter up to `max_attempts` |
| FR-12 | Move exhausted tasks to DEAD_LETTER state |
| FR-13 | Automatically recover tasks stuck in RUNNING when their lease expires |

### Scheduling

| ID | Requirement |
|---|---|
| FR-14 | A scheduler polls for tasks whose `scheduled_at ≤ now` and dispatches them to the queue |
| FR-15 | Multiple scheduler instances can run concurrently without double-dispatching |
| FR-16 | Recurring tasks auto-generate the next occurrence after successful execution |

### Observability

| ID | Requirement |
|---|---|
| FR-17 | Expose Prometheus-compatible metrics |
| FR-18 | Produce structured JSON logs with task_id, worker_id, attempt count |
| FR-19 | Propagate trace context (OpenTelemetry) across API → Scheduler → Queue → Worker |

### Operations

| ID | Requirement |
|---|---|
| FR-20 | Health, readiness, and liveness endpoints (Spring Actuator) |
| FR-21 | Entire stack starts with `docker compose up` |
| FR-22 | Grafana dashboards for task throughput, latency percentiles, queue depth, worker utilization |

---

## 3. Non-Functional Requirements

| Category | Requirement | Notes |
|---|---|---|
| **Durability** | Accepted tasks survive process crashes | PostgreSQL commit before 201 response |
| **Availability** | No single point of failure in the data path | Multiple API, scheduler, worker instances |
| **Scalability** | Support 1K–100K tasks/day with horizontal scaling | Add workers to increase throughput |
| **Latency** | Scheduling latency bounded by poll interval | Configurable; default 5s |
| **Reliability** | At-least-once processing with idempotency | Not exactly-once |
| **Recovery** | Stuck tasks auto-recover via lease expiry | Default lease: 60s, configurable |
| **Observability** | Every state transition is logged and metered | Structured logs + Micrometer counters |
| **Security** | No hardcoded secrets; input validation; rate limiting | Secrets via env vars / K8s Secrets |
| **Testability** | Reproducible failure scenario tests | Testcontainers for real infra |
| **Operability** | One-command local environment; Helm charts for K8s | `docker compose up` / `helm install` |

> **Note on performance targets**: No specific throughput or latency numbers are stated here. All performance claims will be derived from actual load tests after implementation.

---

## 4. System Architecture

### High-Level Component Diagram

```
                    ┌─────────────────────────┐
                    │        Clients           │
                    │  (REST / programmatic)   │
                    └────────────┬─────────────┘
                                 │ HTTPS
                    ┌────────────▼─────────────┐
                    │      API Service          │
                    │  (Spring Boot × N)        │
                    │  • Validate & persist     │
                    │  • Idempotency check      │
                    │  • Return task ID         │
                    └────────┬─────────┬────────┘
                             │         │
              ┌──────────────▼──┐  ┌───▼──────────────┐
              │   PostgreSQL    │  │     Redis         │
              │  (Source of     │  │  (Rate limiting,  │
              │   Truth)        │  │   distributed     │
              │                 │  │   locks — only    │
              │  • Task state   │  │   where justified)│
              │  • Audit trail  │  │                   │
              └──────┬──────┬──┘  └───────────────────┘
                     │      │
          ┌──────────▼──┐   │
          │  Scheduler   │  │ (reads task table)
          │  Service     │◄─┘
          │ (Spring Boot │
          │   × M)       │
          │              │
          │ • Poll for   │
          │   due tasks  │
          │ • Claim via  │
          │   FOR UPDATE │
          │   SKIP LOCKED│
          │ • Publish to │
          │   RabbitMQ   │
          └──────┬───────┘
                 │ AMQP publish
          ┌──────▼───────┐
          │   RabbitMQ    │
          │              │
          │ • task.high   │
          │ • task.medium │
          │ • task.low    │
          │ • task.dlq    │
          └──────┬───────┘
                 │ AMQP consume
      ┌──────────┼──────────┐
      │          │          │
┌─────▼────┐┌───▼─────┐┌───▼─────┐
│ Worker 1 ││Worker 2 ││Worker N │
│(Spring   ││         ││         │
│ Boot)    ││         ││         │
│          ││         ││         │
│• Consume ││         ││         │
│• Execute ││         ││         │
│• ACK/NACK││         ││         │
│• Update  ││         ││         │
│  DB state││         ││         │
└──────────┘└─────────┘└─────────┘
      │          │          │
      └──────────┼──────────┘
                 │ (write back to PostgreSQL)
                 ▼
          Task state updated
          (SUCCESS / RETRY_WAIT / FAILED / DEAD_LETTER)
```

### Data-Flow Summary

```
Client ──POST──▶ API ──INSERT──▶ PostgreSQL (status=SCHEDULED)
                                       │
Scheduler (poll loop) ◀── SELECT ... FOR UPDATE SKIP LOCKED
                                       │
Scheduler ──publish──▶ RabbitMQ ──UPDATE──▶ PostgreSQL (status=QUEUED)
                                       │
Worker ◀── consume ── RabbitMQ
Worker ──UPDATE──▶ PostgreSQL (status=RUNNING, lease_expires_at=now+60s)
Worker ── execute task handler ──
Worker ──UPDATE──▶ PostgreSQL (status=SUCCESS | RETRY_WAIT | FAILED)
Worker ── ACK/NACK ──▶ RabbitMQ
```

### Key architectural decision: PostgreSQL is the single source of truth

RabbitMQ is a **transport mechanism**, not a state store. If RabbitMQ loses a message, the task still exists in PostgreSQL in `QUEUED` status. The lease-expiry recovery mechanism will detect it and re-dispatch.

**Why**: This avoids the split-brain problem where queue state and database state diverge. PostgreSQL's ACID transactions give us atomic state transitions that a message broker cannot.

**Alternative considered**: Making the queue the source of truth (Kafka log style). Rejected because task metadata (status, attempts, errors, schedule) requires relational queries, and Kafka's append-only log doesn't support efficient status queries or atomic state transitions.

**Trade-off**: Extra database writes on every state transition. Acceptable at our target scale (1K–100K tasks/day).

---

## 5. Component Responsibilities

### API Service

| Responsibility | Details |
|---|---|
| Accept task submissions | Validate payload, check idempotency key, persist to PostgreSQL |
| Query task state | Read from PostgreSQL |
| Cancel tasks | Atomic state transition: `SCHEDULED`/`QUEUED` → `CANCELLED` |
| Manual retry | Transition `DEAD_LETTER` → `SCHEDULED` with reset `attempt_count` |
| Health/readiness | Spring Actuator endpoints |

The API service is **stateless**. Multiple instances can run behind a load balancer.

### Scheduler Service

| Responsibility | Details |
|---|---|
| Poll for due tasks | `SELECT ... WHERE status = 'SCHEDULED' AND scheduled_at <= now() FOR UPDATE SKIP LOCKED` |
| Claim tasks | Atomic `UPDATE status = 'QUEUED'` within the same transaction |
| Dispatch to queue | Publish task reference (task_id + metadata) to RabbitMQ |
| Handle recurring tasks | After a recurring task completes, compute `next_run_at` from cron expression and insert a new task occurrence |

The scheduler **does not execute** tasks. It is a dispatcher.

### Worker Service

| Responsibility | Details |
|---|---|
| Consume from RabbitMQ | Prefetch-limited consumption |
| Acquire lease | `UPDATE status = 'RUNNING', lease_expires_at = now() + lease_duration WHERE id = ? AND status = 'QUEUED'` |
| Execute handler | Delegate to the appropriate `TaskHandler` based on `task_type` |
| Record outcome | `SUCCESS`, `RETRY_WAIT` (with next retry time), or `FAILED` / `DEAD_LETTER` |
| ACK/NACK | Acknowledge the RabbitMQ message after DB state is updated |
| Heartbeat/renew lease | For long-running tasks, periodically extend `lease_expires_at` |
| Graceful shutdown | On SIGTERM, stop consuming new tasks, wait for in-flight tasks to complete (with timeout) |

### PostgreSQL

Single source of truth for all task metadata, state, and audit trail.

### RabbitMQ

Asynchronous transport between scheduler and workers. Provides buffering, fan-out to multiple workers, consumer-level acknowledgement, and dead-lettering.

### Redis

**Justified uses only**:

| Use case | Justification |
|---|---|
| API rate limiting | High-frequency check; Redis `INCR` with TTL is efficient. A DB round-trip per request for rate limiting is excessive. |
| Distributed lock for scheduler leader election (optional) | Only if we want a "primary scheduler" model. Not required if `FOR UPDATE SKIP LOCKED` partitioning is sufficient. |

Redis is **not** the source of truth for any task data. If Redis is unavailable, the system degrades gracefully (rate limiting fails open or is handled by local in-memory fallback).

---

## 6. Complete Task Lifecycle

### One-Shot Task (Immediate)

```
1. Client POST /api/v1/tasks { type, payload, priority }
2. API validates → inserts row (status=SCHEDULED, scheduled_at=now)
3. API returns 201 Created { id }
4. Scheduler poll detects scheduled_at ≤ now
5. Scheduler claims task (SCHEDULED → QUEUED) via FOR UPDATE SKIP LOCKED
6. Scheduler publishes {task_id} to RabbitMQ priority queue
7. Worker consumes message
8. Worker claims lease (QUEUED → RUNNING, lease_expires_at = now+60s)
9. Worker resolves TaskHandler by task_type
10. Worker executes handler
11a. Success → Worker updates (RUNNING → SUCCESS, completed_at=now), ACKs message
11b. Failure + retries remaining → Worker updates (RUNNING → RETRY_WAIT, next_retry_at), ACKs message
11c. Failure + retries exhausted → Worker updates (RUNNING → DEAD_LETTER), ACKs message
```

### One-Shot Task (Deferred)

Same as immediate, except `scheduled_at` is in the future. The scheduler will not pick it up until that time.

### Recurring Task

```
1. Client POST /api/v1/tasks { type, payload, cron_expression, recurrence_enabled=true }
2. API computes next_run_at from cron_expression, inserts row (status=SCHEDULED, scheduled_at=next_run_at)
3. Steps 4–11a same as one-shot
4. On SUCCESS, scheduler (or worker) computes next_run_at from cron_expression
5. New task row inserted (status=SCHEDULED, scheduled_at=next_run_at) — the parent task row stays in SUCCESS
6. Cycle repeats
```

### Cancellation

```
Client POST /api/v1/tasks/{id}/cancel
→ API atomically: UPDATE status = 'CANCELLED' WHERE id = ? AND status IN ('SCHEDULED', 'QUEUED')
→ If 0 rows affected: task is already RUNNING/completed → 409 Conflict
```

### Manual Retry (Dead-Letter Recovery)

```
Client POST /api/v1/tasks/{id}/retry
→ API atomically: UPDATE status = 'SCHEDULED', attempt_count = 0, scheduled_at = now
   WHERE id = ? AND status = 'DEAD_LETTER'
→ Task re-enters normal lifecycle
```

---

## 7. Task State Machine

### States

| State | Description |
|---|---|
| `SCHEDULED` | Persisted, waiting for `scheduled_at` to arrive |
| `QUEUED` | Claimed by scheduler, published to RabbitMQ, waiting for a worker |
| `RUNNING` | A worker has acquired a lease and is executing the task |
| `SUCCESS` | Execution completed successfully (terminal) |
| `RETRY_WAIT` | Execution failed, waiting for next retry time |
| `FAILED` | Execution failed, retries exhausted but not yet moved to DLQ |
| `DEAD_LETTER` | Permanently failed, isolated for operator inspection (terminal unless manually retried) |
| `CANCELLED` | Cancelled by user (terminal) |

### Legal Transitions

```
SCHEDULED   → QUEUED        (scheduler claims and dispatches)
SCHEDULED   → CANCELLED     (user cancellation)

QUEUED      → RUNNING       (worker acquires lease)
QUEUED      → CANCELLED     (user cancellation)
QUEUED      → SCHEDULED     (lease-expiry recovery if stuck in QUEUED too long)

RUNNING     → SUCCESS       (execution succeeded)
RUNNING     → RETRY_WAIT    (execution failed, retries remain)
RUNNING     → FAILED        (execution failed, no retries remain)
RUNNING     → QUEUED        (lease expired — recovery resets for re-dispatch)

RETRY_WAIT  → SCHEDULED     (retry delay elapsed, re-enters scheduling)

FAILED      → DEAD_LETTER   (automatic escalation)

DEAD_LETTER → SCHEDULED     (manual retry by operator)
```

### Illegal Transitions (enforced in code)

- `SUCCESS → *` (terminal)
- `CANCELLED → *` (terminal)
- `RUNNING → SCHEDULED` (must go through RETRY_WAIT or lease-expiry recovery)
- `QUEUED → SUCCESS` (must go through RUNNING)

### Enforcement

Every state transition is implemented as an atomic conditional `UPDATE`:

```sql
UPDATE tasks
SET status = :newStatus, updated_at = now()
WHERE id = :taskId AND status = :expectedCurrentStatus
```

If the `WHERE` clause matches 0 rows, the transition is rejected. This is a compare-and-swap (CAS) pattern at the database level.

**Why not application-level validation only?** Because in a concurrent system, two processes might read the same state and both attempt a transition. The DB-level CAS ensures exactly one succeeds.

---

## 8. Database Schema

### Primary Table: `tasks`

```sql
CREATE TABLE tasks (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    task_type         VARCHAR(50) NOT NULL,          -- EMAIL, HTTP, DEMO
    payload           JSONB       NOT NULL,          -- task-specific data
    status            VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',
    priority          VARCHAR(10) NOT NULL DEFAULT 'MEDIUM',  -- HIGH, MEDIUM, LOW

    -- Scheduling
    scheduled_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at        TIMESTAMPTZ,
    completed_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Retry
    attempt_count     INT         NOT NULL DEFAULT 0,
    max_attempts      INT         NOT NULL DEFAULT 5,
    next_retry_at     TIMESTAMPTZ,

    -- Lease (worker crash recovery)
    worker_id         VARCHAR(100),
    lease_id          UUID,
    lease_expires_at  TIMESTAMPTZ,

    -- Idempotency
    idempotency_key   VARCHAR(255),

    -- Error tracking
    last_error        TEXT,

    -- Recurrence
    cron_expression   VARCHAR(100),
    recurrence_enabled BOOLEAN    NOT NULL DEFAULT false,
    parent_task_id    UUID        REFERENCES tasks(id),

    -- Audit
    created_by        VARCHAR(100),

    -- Constraints
    CONSTRAINT chk_status CHECK (status IN (
        'SCHEDULED', 'QUEUED', 'RUNNING', 'SUCCESS',
        'RETRY_WAIT', 'FAILED', 'DEAD_LETTER', 'CANCELLED'
    )),
    CONSTRAINT chk_priority CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT chk_max_attempts CHECK (max_attempts >= 1 AND max_attempts <= 20)
);
```

### Indexes

```sql
-- Scheduler polling: find due tasks efficiently
CREATE INDEX idx_tasks_scheduler_poll
    ON tasks (scheduled_at)
    WHERE status = 'SCHEDULED';

-- Lease recovery: find stuck RUNNING tasks
CREATE INDEX idx_tasks_lease_recovery
    ON tasks (lease_expires_at)
    WHERE status = 'RUNNING';

-- Retry recovery: find tasks ready for retry
CREATE INDEX idx_tasks_retry
    ON tasks (next_retry_at)
    WHERE status = 'RETRY_WAIT';

-- Idempotency: fast duplicate detection
CREATE UNIQUE INDEX idx_tasks_idempotency_key
    ON tasks (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- API queries: list by status
CREATE INDEX idx_tasks_status
    ON tasks (status, created_at DESC);

-- API queries: list by type
CREATE INDEX idx_tasks_type
    ON tasks (task_type, created_at DESC);
```

### Why these indexes?

| Index | Query it serves | Why partial? |
|---|---|---|
| `idx_tasks_scheduler_poll` | Scheduler `SELECT ... WHERE status='SCHEDULED' AND scheduled_at <= now()` | Partial on `status='SCHEDULED'` to keep the index small — most tasks are in terminal states |
| `idx_tasks_lease_recovery` | Recovery sweep `WHERE status='RUNNING' AND lease_expires_at < now()` | Partial on `status='RUNNING'` — only a small fraction of tasks are running at any time |
| `idx_tasks_retry` | Retry sweep `WHERE status='RETRY_WAIT' AND next_retry_at <= now()` | Partial on `status='RETRY_WAIT'` |
| `idx_tasks_idempotency_key` | Duplicate submission detection | Unique + partial (only non-null keys) to allow multiple tasks without keys |

### Task Execution History Table: `task_attempts`

```sql
CREATE TABLE task_attempts (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id       UUID        NOT NULL REFERENCES tasks(id),
    attempt_number INT        NOT NULL,
    worker_id     VARCHAR(100),
    started_at    TIMESTAMPTZ NOT NULL,
    completed_at  TIMESTAMPTZ,
    status        VARCHAR(20) NOT NULL,  -- SUCCESS, FAILED
    error_message TEXT,
    duration_ms   BIGINT,

    CONSTRAINT uq_task_attempt UNIQUE (task_id, attempt_number)
);

CREATE INDEX idx_task_attempts_task_id ON task_attempts (task_id);
```

**Why a separate table?** The `tasks` table tracks current state. The `task_attempts` table provides a full audit trail of every execution attempt — essential for debugging, observability, and answering "what happened to this task?"

### Database Migration Strategy

Flyway versioned migrations:

```
db/migration/
├── V1__create_tasks_table.sql
├── V2__create_task_attempts_table.sql
├── V3__add_indexes.sql
├── V4__add_idempotency_key_unique_index.sql
```

Each migration is idempotent and forward-only. No `ALTER` without a migration file. The schema is reproducible from a fresh database.

---

## 9. API Design

### Base Path

```
/api/v1
```

### Endpoints

| Method | Path | Description | Success | Key Error Codes |
|---|---|---|---|---|
| `POST` | `/tasks` | Create a task | `201 Created` | `400` (validation), `409` (duplicate idempotency key) |
| `GET` | `/tasks/{id}` | Get task by ID | `200 OK` | `404` (not found) |
| `GET` | `/tasks` | List tasks (filterable, paginated) | `200 OK` | `400` (invalid filter) |
| `POST` | `/tasks/{id}/cancel` | Cancel a task | `200 OK` | `404`, `409` (not cancellable) |
| `POST` | `/tasks/{id}/retry` | Retry a dead-lettered task | `200 OK` | `404`, `409` (not in DEAD_LETTER) |
| `DELETE` | `/tasks/{id}` | Soft-delete a task | `204 No Content` | `404` |

### Create Task Request

```json
{
  "taskType": "EMAIL",
  "payload": {
    "to": "user@example.com",
    "subject": "Reminder",
    "body": "Your report is ready."
  },
  "scheduledAt": "2026-08-30T10:00:00Z",    // optional, defaults to now
  "priority": "HIGH",                         // optional, defaults to MEDIUM
  "maxAttempts": 5,                           // optional, defaults to 5
  "idempotencyKey": "email-reminder-42",      // optional
  "cronExpression": "0 0 9 * * ?",            // optional, makes it recurring
  "callbackUrl": "https://myservice/webhook"  // optional, for webhook dispatch
}
```

### Create Task Response

```json
{
  "id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "taskType": "EMAIL",
  "status": "SCHEDULED",
  "priority": "HIGH",
  "scheduledAt": "2026-08-30T10:00:00Z",
  "maxAttempts": 5,
  "attemptCount": 0,
  "createdAt": "2026-08-28T14:00:00Z"
}
```

### Error Response Format (Consistent)

```json
{
  "error": {
    "code": "TASK_NOT_CANCELLABLE",
    "message": "Task f47ac... is in status RUNNING and cannot be cancelled.",
    "status": 409,
    "timestamp": "2026-08-28T14:05:00Z",
    "path": "/api/v1/tasks/f47ac.../cancel"
  }
}
```

### Pagination (Cursor-Based)

```
GET /api/v1/tasks?status=FAILED&limit=20&cursor=eyJpZCI6Ii4uLiJ9
```

Response includes:

```json
{
  "data": [...],
  "pagination": {
    "nextCursor": "eyJpZCI6Ii4uLiJ9",
    "hasMore": true
  }
}
```

**Why cursor-based, not offset-based?** Offset pagination (`OFFSET 1000`) scans and discards 1000 rows. Cursor pagination (`WHERE created_at < :cursor`) uses the index directly. At scale, cursor pagination is O(limit) not O(offset + limit).

### Idempotency via `Idempotency-Key` Header

If the client sends `Idempotency-Key: email-reminder-42` in the `POST /tasks` header (or the `idempotencyKey` field in the body), the API:

1. Checks if a task with that key already exists.
2. If it exists and is not in a terminal failure state → returns the existing task (200 OK, not 201).
3. If it doesn't exist → creates and returns 201.

This protects against network retries from the client side.

### Validation Rules

| Field | Rules |
|---|---|
| `taskType` | Required. Must be one of the registered types (EMAIL, HTTP, DEMO). |
| `payload` | Required. Must be valid JSON. Size limit: 64 KB. |
| `scheduledAt` | If present, must not be in the past by more than 5 minutes. |
| `priority` | Must be HIGH, MEDIUM, or LOW. |
| `maxAttempts` | 1–20. |
| `cronExpression` | If present, must be a valid Quartz-compatible cron expression. |

### API Documentation

OpenAPI 3.0 spec auto-generated via `springdoc-openapi`. Available at `/swagger-ui.html` in dev/staging.

---

## 10. Scheduler Design

### What

The scheduler is a background service that continuously polls PostgreSQL for tasks whose `scheduled_at` time has arrived and dispatches them to RabbitMQ for worker consumption.

### Why

The scheduler exists to **decouple task acceptance from task execution**. The API accepts and persists tasks. The scheduler decides when they should be dispatched. This separation enables:

- Deferred execution (tasks scheduled in the future)
- Buffering (burst of submissions doesn't overwhelm workers)
- Centralized dispatch logic

### How It Works

```
┌────────────────────────────────────────────┐
│              Scheduler Poll Loop           │
│                                            │
│  while (running):                          │
│    sleep(pollInterval)     // 5s default   │
│                                            │
│    BEGIN TRANSACTION                       │
│                                            │
│    SELECT id, task_type, priority          │
│    FROM tasks                              │
│    WHERE status = 'SCHEDULED'             │
│      AND scheduled_at <= now()            │
│    ORDER BY                               │
│      CASE priority                        │
│        WHEN 'HIGH' THEN 1                 │
│        WHEN 'MEDIUM' THEN 2              │
│        WHEN 'LOW' THEN 3                 │
│      END,                                 │
│      scheduled_at ASC                     │
│    LIMIT :batchSize         // 50 default │
│    FOR UPDATE SKIP LOCKED                 │
│                                            │
│    for each task:                          │
│      UPDATE tasks                         │
│        SET status = 'QUEUED',            │
│            updated_at = now()            │
│        WHERE id = :id                    │
│                                            │
│      publish(task) → RabbitMQ            │
│                                            │
│    COMMIT TRANSACTION                     │
│                                            │
└────────────────────────────────────────────┘
```

### Critical Design: Publish-then-Commit vs Commit-then-Publish

There are two orderings:

**Option A: Publish to RabbitMQ, then commit DB transaction**
- Risk: If DB commit fails after RabbitMQ publish, the message is in the queue but the task is still `SCHEDULED` in the DB. Worker may pick up a task that's not in `QUEUED` state. The worker's lease-acquisition step (`WHERE status = 'QUEUED'`) will reject it — safe but wasteful.

**Option B: Commit DB transaction, then publish to RabbitMQ**
- Risk: If RabbitMQ publish fails after DB commit, the task is `QUEUED` in the DB but no message exists in the queue. Task appears stuck. However, the lease-expiry recovery sweep will detect it and re-dispatch — self-healing.

**We choose Option B**. The failure mode is self-healing: a `QUEUED` task with no queue message will be detected by the stale-task recovery sweep and re-dispatched. This is safer than Option A because we never have a message in the queue for a task that the DB thinks is still `SCHEDULED`.

### Retry-Wait Recovery

A second poll loop (or the same loop with a different query) handles `RETRY_WAIT` tasks:

```sql
SELECT id FROM tasks
WHERE status = 'RETRY_WAIT'
  AND next_retry_at <= now()
FOR UPDATE SKIP LOCKED
```

These tasks are transitioned back to `SCHEDULED` so the main poll picks them up.

### Polling Interval Trade-off

| Interval | Scheduling Latency | DB Load |
|---|---|---|
| 1s | ~1s | Higher (more frequent queries) |
| 5s | ~5s | Moderate |
| 30s | ~30s | Lower |

Default: **5 seconds**. Configurable via `scheduler.poll-interval` property.

### Alternatives Considered

| Alternative | Why Not |
|---|---|
| **PostgreSQL LISTEN/NOTIFY** | Event-driven, lower latency. But adds complexity; doesn't support `SKIP LOCKED` partitioning for multiple schedulers natively. Can be a future optimization. |
| **Change Data Capture (Debezium)** | Overkill for this scale. Adds Kafka dependency. |
| **In-memory timer (ScheduledExecutorService)** | Loses scheduled tasks on crash. Not durable. |

### Failure Scenarios

| Failure | Impact | Recovery |
|---|---|---|
| Scheduler crashes mid-transaction | Transaction rolls back. Tasks remain `SCHEDULED`. | Next scheduler poll picks them up. |
| Scheduler crashes after commit but before RabbitMQ publish | Tasks stuck in `QUEUED` with no queue message. | Stale-task recovery sweep re-dispatches. |
| All schedulers down | No new tasks dispatched. Tasks accumulate in `SCHEDULED`. | Once any scheduler restarts, backlog is processed. |

---

## 11. Queue Design

### What

RabbitMQ serves as the asynchronous transport between schedulers and workers.

### Why

- **Buffering**: If 10,000 tasks become due simultaneously, the queue absorbs the burst. Workers consume at their own rate.
- **Decoupling**: Schedulers and workers don't need to know about each other.
- **Horizontal scaling**: Add more worker instances → more consumers → higher throughput.
- **Acknowledgement**: RabbitMQ redelivers unacknowledged messages if a consumer disconnects.

### Topology

```
                    ┌─────────────────────────────────┐
                    │     task.exchange (direct)       │
                    └──┬──────────┬──────────┬────────┘
                       │          │          │
                routing keys:  high      medium      low
                       │          │          │
                  ┌────▼───┐ ┌───▼────┐ ┌───▼────┐
                  │task.   │ │task.   │ │task.   │
                  │high    │ │medium  │ │low     │
                  └───┬────┘ └───┬────┘ └───┬────┘
                      │          │          │
              DLX routing on rejection/expiry
                      │          │          │
                  ┌───▼──────────▼──────────▼──┐
                  │    task.dlx.exchange        │
                  └──────────┬─────────────────┘
                             │
                  ┌──────────▼──────────┐
                  │    task.dlq         │
                  │  (Dead Letter Queue)│
                  └─────────────────────┘
```

### Queue Configuration

| Property | Value | Rationale |
|---|---|---|
| Durable | `true` | Survive broker restart |
| Exclusive | `false` | Multiple consumers |
| Auto-delete | `false` | Persist even when no consumers |
| `x-dead-letter-exchange` | `task.dlx.exchange` | Route rejected messages to DLQ |
| `x-message-ttl` | Not set (handled at application level) | We control retry timing via DB, not queue TTL |

### Message Format

```json
{
  "taskId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "taskType": "EMAIL",
  "priority": "HIGH",
  "attemptCount": 0,
  "dispatchedAt": "2026-08-28T14:05:00Z"
}
```

Messages are **lightweight references**. The full payload is fetched from PostgreSQL by the worker. This keeps messages small and ensures the DB is always the source of truth.

**Why not put the full payload in the message?** Because if the task is cancelled between dispatch and consumption, the worker would execute a cancelled task. By reading from the DB, the worker always sees current state.

### Consumer Prefetch

```
spring.rabbitmq.listener.simple.prefetch = 5
```

Each worker prefetches 5 messages. This balances throughput (fewer round-trips to broker) against fairness (don't starve other workers).

### Acknowledgement Strategy

- **Manual ACK**: Worker ACKs only after the task outcome is persisted to PostgreSQL.
- **NACK without requeue**: On unrecoverable errors, NACK the message. RabbitMQ routes it to the DLQ.
- **NACK with requeue**: Not used. Retries are managed at the application level via `RETRY_WAIT` state and `next_retry_at`.

### Alternatives Considered

| Alternative | Trade-off |
|---|---|
| **Apache Kafka** | Log-based, excellent for event streaming and replay. But adds operational complexity (ZooKeeper/KRaft, partitions, consumer groups). RabbitMQ is simpler for task queuing with ACK/NACK semantics. |
| **Redis Streams** | Lightweight, already in the stack. But lacks the mature dead-lettering, routing, and management UI of RabbitMQ. |
| **PostgreSQL as queue (poll-only, no broker)** | Eliminates a dependency. But `SELECT FOR UPDATE SKIP LOCKED` polling from workers is less efficient than push-based consumption, and doesn't provide backpressure natively. |

### Failure Scenarios

| Failure | Impact | Recovery |
|---|---|---|
| RabbitMQ down during publish | Scheduler publish fails; DB transaction can still commit (task in QUEUED). | Stale-task recovery re-dispatches when RabbitMQ returns. |
| RabbitMQ loses a message | Task is QUEUED in DB but no message in queue. | Stale-task recovery detects and re-dispatches. |
| Consumer crashes before ACK | RabbitMQ redelivers to another consumer. Worker checks DB state before executing. | Automatic via RabbitMQ redelivery. |

---

## 12. Worker Design

### What

Workers are Spring Boot services that consume task messages from RabbitMQ, execute the appropriate handler, and record outcomes in PostgreSQL.

### Why

Separating execution into dedicated worker processes provides:

- **Fault isolation**: A worker crash doesn't affect the API or scheduler.
- **Horizontal scaling**: Add more worker pods to increase throughput.
- **Resource control**: Workers have independent CPU/memory limits.

### Internal Architecture

```
┌─────────────────────────────────────────────────┐
│                   Worker Service                │
│                                                 │
│  ┌──────────────────┐  ┌─────────────────────┐  │
│  │ RabbitMQ Listener │  │  TaskHandlerRegistry│  │
│  │ (SimpleMessage-  │  │                     │  │
│  │  ListenerContainer│  │  EMAIL → EmailHandler│ │
│  │  Factory)         │  │  HTTP  → HttpHandler │ │
│  │                   │  │  DEMO  → DemoHandler │ │
│  │  prefetch=5       │  │                     │  │
│  └────────┬──────────┘  └──────────┬──────────┘  │
│           │                        │              │
│  ┌────────▼────────────────────────▼──────────┐  │
│  │           TaskExecutionService              │  │
│  │                                             │  │
│  │  1. Fetch task from DB                      │  │
│  │  2. Validate state = QUEUED                 │  │
│  │  3. CAS: QUEUED → RUNNING + set lease       │  │
│  │  4. Resolve handler                         │  │
│  │  5. Execute with timeout                    │  │
│  │  6. Record outcome                          │  │
│  │  7. ACK/NACK RabbitMQ message              │  │
│  └─────────────────────────────────────────────┘  │
│                                                 │
│  ┌─────────────────────────────────────────────┐  │
│  │         LeaseRenewalService                 │  │
│  │  (ScheduledExecutorService)                 │  │
│  │                                             │  │
│  │  For long-running tasks:                    │  │
│  │  UPDATE lease_expires_at = now() + 60s      │  │
│  │  WHERE id = ? AND lease_id = ?              │  │
│  │  every 20s                                  │  │
│  └─────────────────────────────────────────────┘  │
│                                                 │
│  ┌─────────────────────────────────────────────┐  │
│  │         GracefulShutdownHandler             │  │
│  │                                             │  │
│  │  On SIGTERM:                                │  │
│  │  1. Stop accepting new messages             │  │
│  │  2. Wait for in-flight tasks (max 30s)      │  │
│  │  3. Cancel remaining, NACK messages         │  │
│  │  4. Shutdown thread pool                    │  │
│  └─────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────┘
```

### Task Handler Interface

```java
public interface TaskHandler {
    String getTaskType();       // e.g., "EMAIL"
    TaskResult execute(TaskContext context) throws Exception;
}
```

Handlers are Spring beans discovered via `TaskHandlerRegistry`. New task types are added by implementing this interface — the system is extensible without modifying core code.

### Internal vs External (Webhook) Execution

| Mode | When | How |
|---|---|---|
| **Internal** | `task_type` has a registered `TaskHandler` bean | Direct method call within the worker JVM |
| **External (webhook)** | Task has a `callbackUrl` in the payload | Worker sends HTTP POST to the callback URL with the task payload. Expects 2xx for success. |

The `HttpTaskHandler` is the built-in handler for webhook dispatch. It uses a `RestClient` with configurable connect/read timeouts.

### Execution Timeout

Each task execution is wrapped in a `Future` with a timeout:

```java
ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
Future<TaskResult> future = executor.submit(() -> handler.execute(context));
TaskResult result = future.get(task.getTimeoutSeconds(), TimeUnit.SECONDS);
```

If the timeout elapses, the future is cancelled, and the task is marked `RETRY_WAIT` or `FAILED`.

**Why virtual threads here?** Virtual threads (Java 21) are ideal for tasks that are I/O-bound (HTTP calls, email sending). They are cheap to create and don't block platform threads during I/O waits. However, CPU-bound task handlers should use platform threads — this is a documented consideration.

### Concurrency Configuration

| Property | Default | Description |
|---|---|---|
| `worker.concurrency` | 5 | Number of concurrent RabbitMQ consumers |
| `worker.max-concurrency` | 10 | Maximum auto-scaled consumers |
| `worker.prefetch` | 5 | Messages prefetched per consumer |
| `worker.task-timeout-seconds` | 60 | Per-task execution timeout |
| `worker.lease-duration-seconds` | 60 | Lease duration for crash recovery |
| `worker.lease-renewal-interval-seconds` | 20 | Lease renewal frequency |
| `worker.shutdown-timeout-seconds` | 30 | Grace period on SIGTERM |

---

## 13. Retry Architecture

### What

When a task execution fails with a transient error, the system retries it with increasing delays rather than immediately marking it as permanently failed.

### Why

Many failures are transient: network timeouts, temporary service unavailability, rate limiting. A retry with backoff often succeeds without human intervention.

### How

```
Attempt 1 → FAIL → wait 1s + jitter
Attempt 2 → FAIL → wait 2s + jitter
Attempt 3 → FAIL → wait 4s + jitter
Attempt 4 → FAIL → wait 8s + jitter
Attempt 5 → FAIL → DEAD_LETTER
```

### Backoff Formula

```
delay = min(base_delay_ms × 2^(attempt - 1), max_delay_ms) + random(0, jitter_ms)
```

Default configuration:

| Parameter | Default |
|---|---|
| `base_delay_ms` | 1000 (1 second) |
| `max_delay_ms` | 300000 (5 minutes) |
| `jitter_ms` | 500 |
| `max_attempts` | 5 (per task, configurable) |

### Retry Flow in Detail

```
Worker executes task → failure
  │
  ├── attempt_count < max_attempts?
  │     │
  │     YES → compute next_retry_at = now() + backoff(attempt_count)
  │          UPDATE tasks SET
  │            status = 'RETRY_WAIT',
  │            attempt_count = attempt_count + 1,
  │            next_retry_at = :computed_time,
  │            last_error = :error_message
  │          WHERE id = :id AND status = 'RUNNING'
  │
  │          ACK the RabbitMQ message (we're done with it; retry is DB-driven)
  │
  │          Scheduler retry-recovery loop picks it up when next_retry_at ≤ now()
  │          Transitions RETRY_WAIT → SCHEDULED → normal dispatch cycle
  │
  └── attempt_count >= max_attempts?
        │
        UPDATE tasks SET
          status = 'DEAD_LETTER',
          attempt_count = attempt_count + 1,
          last_error = :error_message,
          completed_at = now()
        WHERE id = :id AND status = 'RUNNING'
        
        ACK the RabbitMQ message
```

### Why Application-Level Retries, Not RabbitMQ Retries?

| Approach | Pros | Cons |
|---|---|---|
| **RabbitMQ NACK + requeue** | Simple | No backoff control, no per-task retry count, retry storms |
| **RabbitMQ DLQ + TTL + re-publish** | Broker-managed delays | Complex topology, limited delay granularity, state split between queue and DB |
| **Application-level (DB-driven)** ✅ | Full control over backoff, per-task retry count, jitter, observable state | Slightly more code |

We choose **application-level retries** because:
1. The retry count, next retry time, and error are all visible in the DB.
2. We can implement arbitrary backoff strategies.
3. The scheduler already has a poll loop — adding a retry-recovery query is trivial.
4. No complex RabbitMQ topology.

### Jitter: Why It Matters

Without jitter, if 1000 tasks fail simultaneously, they all retry at exactly `now + 4s`, creating a retry storm. Jitter spreads them over a window, preventing thundering herd.

---

## 14. Idempotency Architecture

### What

Idempotency ensures that processing the same logical task multiple times produces the same result as processing it once. This is necessary because at-least-once delivery means duplicate processing **will** occur.

### Two Layers of Idempotency

#### Layer 1: Task Submission Idempotency (API Level)

Prevents duplicate task creation due to client retries.

```
Client → POST /tasks { idempotencyKey: "payment-123" }
         ← 201 Created

Client (retry) → POST /tasks { idempotencyKey: "payment-123" }
                 ← 200 OK (returns existing task, does not create duplicate)
```

Implementation: `UNIQUE INDEX` on `idempotency_key` column. On `INSERT`, catch the unique violation and return the existing task.

#### Layer 2: Task Execution Idempotency (Worker Level)

Prevents duplicate side effects when a task is re-executed after a worker crash.

This is **not** something the scheduler infrastructure can universally solve. It depends on the task handler:

| Task Type | Idempotency Strategy |
|---|---|
| **EMAIL** | Include `idempotency_key` in the email provider's API call (e.g., SendGrid's idempotency header). If the provider doesn't support it, log a warning — duplicate emails are the known trade-off. |
| **HTTP (webhook)** | Include the `idempotency_key` and `task_id` in the webhook payload. The receiving service is responsible for deduplication. |
| **DEMO** | Inherently idempotent (no real side effects). |

### Worker-Level Guard

Before executing, the worker performs a "stale execution" check:

```sql
SELECT status, attempt_count FROM tasks WHERE id = :taskId
```

If the task is already `SUCCESS` or `CANCELLED`, the worker skips execution and ACKs the message. This catches the case where:
1. Worker A executes and succeeds.
2. Worker B receives a redelivered message for the same task.
3. Worker B reads status = SUCCESS → skips execution.

### What This System Does NOT Claim

> This system does **not** guarantee exactly-once execution of external side effects. It provides at-least-once processing with idempotency primitives. Whether the actual side effect is truly idempotent depends on the task handler implementation and the external service.

This is a critical architectural honesty point for interviews.

---

## 15. Failure Recovery

### Failure Recovery Matrix

| Failure | Detection | Recovery Mechanism | Data Loss? |
|---|---|---|---|
| **Worker crash during execution** | `lease_expires_at < now()` for RUNNING tasks | Lease-expiry sweep transitions RUNNING → QUEUED (or RETRY_WAIT if attempt counted) | No — task is in DB |
| **Scheduler crash** | Scheduler stops polling | Other scheduler instances continue. If single scheduler, tasks accumulate until restart. | No — tasks remain SCHEDULED in DB |
| **RabbitMQ message lost** | QUEUED tasks with no consumer activity past threshold | Stale-QUEUED sweep: tasks in QUEUED for > 2× lease duration → re-dispatch | No — task is in DB |
| **RabbitMQ broker down** | Scheduler publish fails | Scheduler catches exception, transaction rolls back, task stays SCHEDULED. Retried on next poll. | No |
| **Database down** | Connection refused / timeout | API returns 503. Scheduler and workers pause with retry. No state changes. | No |
| **Network partition** | Timeouts | Lease expiry handles workers that can't reach DB. Tasks are reprocessed after partition heals. | No — may get duplicate processing (at-least-once) |
| **Duplicate message delivery** | Worker reads current task status before executing | If already SUCCESS/CANCELLED, worker skips and ACKs | No |
| **Slow task exceeding lease** | `lease_expires_at` passes while task still running | If no heartbeat renewal: two workers may run same task (at-least-once). Heartbeat renewal prevents this for well-behaved long tasks. | No data loss; possible duplicate execution |

### Lease-Expiry Recovery Sweep

A background job (in the scheduler or a dedicated recovery service) runs periodically:

```sql
-- Find tasks stuck in RUNNING with expired leases
UPDATE tasks
SET status = 'QUEUED',
    worker_id = NULL,
    lease_id = NULL,
    lease_expires_at = NULL,
    updated_at = now()
WHERE status = 'RUNNING'
  AND lease_expires_at < now()
RETURNING id;
```

These tasks are then re-dispatched through the normal scheduler → queue → worker path.

**Frequency**: Every 30 seconds (configurable).

### Stale-QUEUED Recovery Sweep

```sql
-- Find tasks stuck in QUEUED for too long (message probably lost)
UPDATE tasks
SET status = 'SCHEDULED',
    updated_at = now()
WHERE status = 'QUEUED'
  AND updated_at < now() - INTERVAL '5 minutes'
RETURNING id;
```

This handles the edge case where the DB committed a QUEUED transition but the RabbitMQ publish failed or the message was lost.

---

## 16. Multi-Scheduler Coordination

### Problem

With multiple scheduler instances polling the same `tasks` table, how do we prevent two schedulers from dispatching the same task?

### Solution: `FOR UPDATE SKIP LOCKED`

This is the primary coordination mechanism. No external distributed lock is needed.

```sql
BEGIN;

SELECT id FROM tasks
WHERE status = 'SCHEDULED' AND scheduled_at <= now()
ORDER BY priority_order, scheduled_at
LIMIT 50
FOR UPDATE SKIP LOCKED;

-- Only this transaction holds locks on these rows
-- Other schedulers' FOR UPDATE SKIP LOCKED will skip these rows

UPDATE tasks SET status = 'QUEUED' WHERE id IN (:lockedIds);

COMMIT;
```

### How It Works

```
Scheduler A                          Scheduler B
    │                                    │
    ├── SELECT ... FOR UPDATE            ├── SELECT ... FOR UPDATE
    │   SKIP LOCKED                      │   SKIP LOCKED
    │   → locks rows {1, 2, 3}          │   → skips {1,2,3}, locks {4, 5, 6}
    │                                    │
    ├── UPDATE → QUEUED                  ├── UPDATE → QUEUED
    ├── COMMIT                           ├── COMMIT
    │                                    │
    No overlap. No distributed lock needed.
```

### Why Not Redis Distributed Locks?

| Aspect | `FOR UPDATE SKIP LOCKED` | Redis Lock (Redlock) |
|---|---|---|
| Dependency | PostgreSQL (already required) | Redis (additional infra) |
| Correctness | Transaction-level guarantees | Best-effort (clock-dependent) |
| Complexity | One SQL clause | Lock acquisition, renewal, release, failure handling |
| Performance | Excellent at our scale | Similar, but extra network hop |
| Failure mode | Transaction rollback releases locks automatically | Lock expiry can cause split-brain |

At our scale (1K–100K tasks/day), `FOR UPDATE SKIP LOCKED` is simpler, more correct, and requires no additional infrastructure. Redis distributed locks would be justified only if we needed cross-service coordination beyond what the DB provides.

### Alternative Considered: Leader Election

A single "active" scheduler with standby replicas (using Redis or ZooKeeper for leader election). Rejected because:
- Single active scheduler is a throughput bottleneck.
- `SKIP LOCKED` naturally partitions work across N schedulers without leader election.
- Leader election adds operational complexity.

### When Would We Add Redis Locks?

If we needed to coordinate something that **isn't** a database row — for example, ensuring only one scheduler runs a periodic cleanup job. In that case, a simple Redis `SET NX EX` lock would be appropriate. This is an optional optimization, not a core requirement.

---

## 17. Concurrency Strategy

### Database Level

| Mechanism | Used For |
|---|---|
| `SELECT ... FOR UPDATE SKIP LOCKED` | Scheduler task claiming — concurrent schedulers partition work without blocking |
| Atomic conditional `UPDATE` (CAS) | All state transitions: `UPDATE ... WHERE status = :expected` — ensures only one writer succeeds |
| `READ COMMITTED` isolation | Default for all transactions. Prevents dirty reads without the overhead of serializable. Sufficient because all critical operations use explicit row locks. |
| Connection pooling (HikariCP) | Bounded connection pool per service. Default: 10 connections. Prevents connection exhaustion. |

### Application Level

| Mechanism | Used For |
|---|---|
| `ThreadPoolExecutor` (bounded) | Worker task execution threads. Bounded to prevent unbounded thread growth. |
| `ScheduledExecutorService` | Scheduler poll loop, lease renewal, recovery sweeps |
| `Semaphore` (optional) | In-process concurrency limiting if virtual threads are used for execution |
| `AtomicReference` / `volatile` | Graceful shutdown flags |

### RabbitMQ Level

| Mechanism | Used For |
|---|---|
| Prefetch limit | Limits in-flight messages per consumer, providing backpressure |
| Manual ACK | Ensures message is not removed from queue until processing completes |
| Competing consumers | Multiple workers on the same queue — RabbitMQ round-robins delivery |

### Race Condition Analysis

| Race Condition | How It's Prevented |
|---|---|
| Two schedulers dispatch the same task | `FOR UPDATE SKIP LOCKED` — second scheduler skips locked rows |
| Two workers execute the same task | CAS on `QUEUED → RUNNING` — only one UPDATE succeeds |
| Worker executes a cancelled task | Worker reads current status before executing; skips if CANCELLED |
| Scheduler dispatches an already-completed task | Scheduler only queries `status = 'SCHEDULED'`; completed tasks have a different status |
| Concurrent cancel + execute | CAS ensures exactly one succeeds. If cancel wins, worker's CAS fails. If execute wins, cancel's CAS fails (returns 409 to client). |

---

## 18. Consistency Model

### PostgreSQL: Strong Consistency

PostgreSQL provides strong consistency for task state. All state transitions are serialized through atomic conditional updates. There is no eventual consistency for task metadata — the DB is always authoritative.

### RabbitMQ: Delivery Consistency

RabbitMQ provides **at-least-once delivery** with manual acknowledgement. A message may be delivered more than once if the consumer crashes before ACK. This is expected and handled by the worker's stale-execution check.

### Redis: Eventual / Best-Effort

Redis is used only for ephemeral state (rate limiting, optional locks). If Redis data is lost, the system does not lose task state. Rate limits may briefly reset — acceptable.

### CAP Analysis

This system prioritizes **CP** (Consistency + Partition tolerance) for task state:

- During a network partition between the application and PostgreSQL, the system **refuses to accept new tasks** (API returns 503) rather than accept them into an inconsistent state.
- This is the correct trade-off for a task scheduler: it is better to reject a submission with a clear error than to accept it and risk losing it.

**What "availability" means here**: The system is available when PostgreSQL is reachable. PostgreSQL's own HA (streaming replication + failover) provides the availability layer for the database.

---

## 19. Reliability Guarantees

### What We Guarantee

| Guarantee | Description |
|---|---|
| **Task durability** | A task that receives a `201 Created` response has been committed to PostgreSQL. It will not be lost due to process crashes. |
| **At-least-once processing** | Every durable task will be executed at least once, provided the system eventually recovers from failures. A task **may** be executed more than once. |
| **Bounded retries** | A task will be retried up to `max_attempts` times with exponential backoff. After exhaustion, it moves to `DEAD_LETTER`. |
| **Crash recovery** | Tasks stuck in `RUNNING` due to worker crashes are automatically detected via lease expiry and re-dispatched. |
| **No silent task loss** | Every task reaches a terminal state: `SUCCESS`, `DEAD_LETTER`, or `CANCELLED`. There is no state where a task can be permanently stuck without detection. |
| **Concurrent safety** | Multiple schedulers and workers operate safely without double-dispatching or double-execution (modulo at-least-once re-delivery). |

### What We Do NOT Guarantee

| Non-Guarantee | Explanation |
|---|---|
| **Exactly-once execution** | A worker may crash after executing a side effect but before recording success. The system will retry, causing duplicate execution. Idempotency keys mitigate this but do not eliminate it. |
| **Strict ordering** | Tasks are approximately ordered by `scheduled_at` and priority, but concurrent schedulers and workers mean execution order is not strictly guaranteed. |
| **Bounded execution latency** | Scheduling latency depends on poll interval. Execution latency depends on worker availability and queue depth. No SLA is claimed. |
| **Survival of permanent infrastructure loss** | If the PostgreSQL data volume is permanently destroyed, tasks are lost. This is mitigated by PostgreSQL backup/replication, which is outside this application's scope. |

---

## 20. Observability

### Three Pillars

#### 1. Metrics (Micrometer → Prometheus → Grafana)

**Counters:**

| Metric | Labels | Description |
|---|---|---|
| `tasks_submitted_total` | `type`, `priority` | Tasks accepted by API |
| `tasks_completed_total` | `type`, `status` | Terminal outcomes (SUCCESS, DEAD_LETTER, CANCELLED) |
| `tasks_retried_total` | `type` | Retry events |
| `tasks_dead_lettered_total` | `type` | Tasks moved to DLQ |
| `scheduler_claims_total` | | Tasks claimed by scheduler |
| `scheduler_dispatches_total` | | Messages published to RabbitMQ |
| `lease_recoveries_total` | | Stuck tasks recovered via lease expiry |

**Gauges:**

| Metric | Description |
|---|---|
| `queue_depth` | Current RabbitMQ queue size |
| `active_workers` | Currently executing workers |
| `tasks_in_status` | Tasks per status (for dashboards) |

**Histograms (percentile distributions):**

| Metric | Description |
|---|---|
| `task_execution_duration_seconds` | Time from RUNNING to outcome |
| `task_scheduling_latency_seconds` | Time from `scheduled_at` to actual dispatch |
| `task_queue_wait_seconds` | Time from QUEUED to RUNNING |
| `task_end_to_end_seconds` | Time from creation to SUCCESS |

#### 2. Structured Logging (SLF4J + Logback → JSON)

Every log entry includes structured context:

```json
{
  "timestamp": "2026-08-28T14:05:00.123Z",
  "level": "INFO",
  "service": "worker",
  "logger": "TaskExecutionService",
  "event": "task_execution_completed",
  "task_id": "f47ac10b-...",
  "task_type": "EMAIL",
  "worker_id": "worker-3",
  "attempt": 2,
  "duration_ms": 450,
  "status": "SUCCESS",
  "trace_id": "abc123def456"
}
```

Key events logged:

| Event | Service | Level |
|---|---|---|
| `task_created` | API | INFO |
| `task_cancelled` | API | INFO |
| `task_claimed` | Scheduler | INFO |
| `task_dispatched` | Scheduler | INFO |
| `task_execution_started` | Worker | INFO |
| `task_execution_completed` | Worker | INFO |
| `task_execution_failed` | Worker | WARN |
| `task_dead_lettered` | Worker | ERROR |
| `lease_expired_recovery` | Scheduler | WARN |
| `idempotency_key_duplicate` | API | INFO |

#### 3. Distributed Tracing (OpenTelemetry → Jaeger/Zipkin)

A trace spans the full task lifecycle:

```
Trace: task-f47ac10b
  ├── Span: api.createTask (API service)
  ├── Span: scheduler.claimTask (Scheduler)
  ├── Span: scheduler.publishToQueue (Scheduler)
  ├── Span: worker.consumeMessage (Worker)
  ├── Span: worker.executeTask (Worker)
  │     └── Span: httpClient.sendEmail (Worker → external)
  └── Span: worker.updateTaskState (Worker)
```

The `task_id` is used as a correlation ID across all services. OpenTelemetry's `traceparent` header is propagated through RabbitMQ message headers.

### Grafana Dashboard Panels

| Panel | Visualization | Data Source |
|---|---|---|
| Task throughput (submit/complete/fail/sec) | Time series | Prometheus counter rate |
| Queue depth | Time series | Prometheus gauge |
| Execution latency (p50, p95, p99) | Heatmap | Prometheus histogram |
| Scheduling latency distribution | Histogram | Prometheus histogram |
| Active workers | Gauge | Prometheus gauge |
| Failure rate | Percentage | Prometheus counter ratio |
| Retry rate | Percentage | Prometheus counter ratio |
| Dead letter rate | Time series | Prometheus counter rate |
| Tasks by status | Pie chart | Prometheus gauge |

---

## 21. Security

### Authentication & Authorization

For this project phase, a simple API key mechanism:

```
X-API-Key: <configured-api-key>
```

The API key is stored as an environment variable / K8s Secret, never hardcoded. This is sufficient for a project demonstration. Spring Security can add RBAC (role-based access control) in a future phase.

### Input Validation

| Rule | Implementation |
|---|---|
| Payload size limit | 64 KB max JSON payload (configured in Spring Boot) |
| Task type whitelist | Only registered types (EMAIL, HTTP, DEMO) accepted |
| Cron expression validation | Parsed and validated before persistence |
| `scheduled_at` sanity check | Not more than 30 days in the future; not more than 5 minutes in the past |
| String length limits | `task_type` ≤ 50, `idempotency_key` ≤ 255, `last_error` ≤ 10000 |

### Rate Limiting

Redis-backed sliding-window rate limiter on `POST /tasks`:

```
Default: 100 requests/minute per API key
```

If Redis is unavailable, fail open (allow requests) — availability is prioritized over rate limiting.

### Secrets Management

| Secret | Storage |
|---|---|
| Database credentials | Environment variables / K8s Secrets |
| RabbitMQ credentials | Environment variables / K8s Secrets |
| Redis password | Environment variables / K8s Secrets |
| API key | Environment variables / K8s Secrets |

**Never committed to Git**: passwords, API keys, tokens, `.env` files. A `.gitignore` entry and a `pre-commit` hook (optional) enforce this.

### Secure Defaults

- HTTPS for all external traffic (terminated at ingress/LB level)
- Internal service-to-service communication over Docker network (no TLS required within cluster for MVP; add mTLS as a future improvement)
- SQL injection: prevented by parameterized queries (JPA/Hibernate)
- Mass assignment: DTOs with explicit field mapping, not direct entity binding

---

## 22. Testing Strategy

### Testing Pyramid

```
                    ╱╲
                   ╱  ╲
                  ╱ E2E ╲          Few, slow, high confidence
                 ╱────────╲
                ╱ Failure   ╲       Moderate, targeted
               ╱─────────────╲
              ╱  Integration   ╲    Moderate, real infra
             ╱──────────────────╲
            ╱    Unit Tests       ╲  Many, fast, isolated
           ╱────────────────────────╲
```

### Unit Tests (JUnit 5 + Mockito)

| What | Example |
|---|---|
| State machine transitions | `SCHEDULED → QUEUED` succeeds; `SUCCESS → QUEUED` throws `IllegalStateTransitionException` |
| Retry backoff calculation | `attempt=3, base=1000` → delay in expected range (4000 ± jitter) |
| Cron next-run computation | `"0 0 9 * * ?"` at 2026-08-28 09:00 → next = 2026-08-29 09:00 |
| Input validation | Missing `taskType` → 400; payload too large → 400 |
| Idempotency key collision | Second insert with same key returns existing task |
| Priority ordering | HIGH tasks are ordered before MEDIUM in scheduler query results |

**Coverage target**: ≥ 80% line coverage on core domain logic (state machine, retry, validation). Not enforced on boilerplate (DTOs, config).

### Integration Tests (Testcontainers)

Real containers for PostgreSQL, RabbitMQ, Redis. Tests verify actual infrastructure interactions.

| What | Framework | Real Infra |
|---|---|---|
| API → PostgreSQL round-trip | `@SpringBootTest` + Testcontainers | PostgreSQL container |
| Scheduler poll + claim | `@SpringBootTest` + Testcontainers | PostgreSQL container |
| Scheduler → RabbitMQ publish | `@SpringBootTest` + Testcontainers | PostgreSQL + RabbitMQ containers |
| Worker consume → execute → update | `@SpringBootTest` + Testcontainers | PostgreSQL + RabbitMQ containers |
| `FOR UPDATE SKIP LOCKED` concurrency | Two concurrent threads simulating two schedulers | PostgreSQL container |
| Idempotency key unique constraint | Insert duplicate key, verify 409 | PostgreSQL container |

### End-to-End Tests

Full lifecycle test with all services running:

```
POST /tasks → SCHEDULED → Scheduler claims → QUEUED → Worker executes → SUCCESS
```

Verified by polling `GET /tasks/{id}` until status = SUCCESS (with timeout).

### Failure/Chaos Tests

| Scenario | How | Verification |
|---|---|---|
| Worker crash during execution | Kill worker container mid-task (docker stop) | Task lease expires → re-dispatched → eventually SUCCESS |
| Scheduler crash | Kill scheduler container | Tasks accumulate; restart scheduler → backlog cleared |
| Duplicate message delivery | Publish same message twice to RabbitMQ | Worker's CAS rejects second execution; task ends in SUCCESS once |
| RabbitMQ down during dispatch | Stop RabbitMQ container, scheduler tries to publish | Scheduler fails gracefully; tasks stay SCHEDULED; restart RabbitMQ → tasks dispatched |
| Database connection pool exhaustion | Reduce pool to 1, submit many concurrent requests | Requests queue and eventually timeout with 503; no data corruption |
| Long-running task vs lease expiry | Task sleeps 120s with 60s lease, no heartbeat | Lease expires, recovery re-dispatches → duplicate (demonstrates at-least-once) |

### Test tooling

| Tool | Purpose |
|---|---|
| JUnit 5 | Test framework |
| Mockito | Mocking for unit tests |
| Testcontainers | Real infra for integration tests |
| AssertJ | Fluent assertions |
| Awaitility | Async condition polling in E2E tests |
| JaCoCo | Code coverage reporting |

---

## 23. Load-Testing Strategy

### Tool: k6

k6 is a modern load testing tool that scripts test scenarios in JavaScript and produces structured metrics.

### Test Scenarios

| Scenario | Description | What It Measures |
|---|---|---|
| **Submission throughput** | Ramp from 10 to 500 concurrent users, each submitting tasks | API p50/p95/p99 latency, max tasks/sec accepted |
| **Scheduling throughput** | Pre-populate 100K SCHEDULED tasks, start scheduler, measure drain rate | Tasks claimed/sec, scheduler CPU/memory |
| **Worker throughput** | Pre-populate queue with 100K messages, start workers, measure completion rate | Tasks executed/sec per worker, total throughput |
| **End-to-end latency** | Submit tasks and measure time to SUCCESS | Full pipeline p50/p95/p99 latency |
| **Burst handling** | Submit 10K tasks in 10 seconds | Queue depth peak, recovery time, no task loss |
| **Scale-out** | Run with 1, 2, 4, 8 workers and measure throughput | Linear scaling factor |

### Workload Tiers

```
Tier 1:      100 tasks      (smoke test)
Tier 2:    1,000 tasks      (basic load)
Tier 3:   10,000 tasks      (moderate load)
Tier 4:  100,000 tasks      (stress test)
```

### Metrics Collected

All metrics from actual test runs. **No fabricated numbers.**

| Metric | How Measured |
|---|---|
| `tasks_submitted_per_second` | k6 request rate |
| `api_latency_p50/p95/p99` | k6 response time percentiles |
| `scheduling_latency_p50/p95/p99` | Prometheus histogram (scheduler) |
| `execution_latency_p50/p95/p99` | Prometheus histogram (worker) |
| `end_to_end_latency_p50/p95/p99` | Computed: `completed_at - created_at` |
| `queue_depth_peak` | Prometheus gauge max |
| `failure_rate` | Prometheus counter ratio |
| `recovery_time_after_crash` | Observed time for stuck tasks to complete after worker restart |

### Results Reporting

After benchmarks, results will be documented in `docs/BENCHMARKS.md` with:
- Hardware/container resource configuration
- Number of replicas
- k6 scenario parameters
- Raw metric tables
- Grafana dashboard screenshots

---

## 24. Docker Architecture

### Container Layout

```
docker-compose.yml
│
├── api          (Spring Boot — task API)
├── scheduler    (Spring Boot — task dispatcher)
├── worker       (Spring Boot — task executor, scalable)
├── postgres     (PostgreSQL 16)
├── rabbitmq     (RabbitMQ 3.13 + management plugin)
├── redis        (Redis 7)
├── prometheus   (Prometheus)
└── grafana      (Grafana + provisioned dashboards)
```

### Dockerfile Strategy

A multi-stage Dockerfile for each Java service:

```dockerfile
# Stage 1: Build
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN ./mvnw -pl services/api -am clean package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:21-jre
COPY --from=build /app/services/api/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Docker Compose Services

```yaml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: taskscheduler
      POSTGRES_USER: ${DB_USER}
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${DB_USER}"]
      interval: 5s
      retries: 5

  rabbitmq:
    image: rabbitmq:3.13-management
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "check_running"]
      interval: 10s

  redis:
    image: redis:7
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]

  api:
    build: { context: ., dockerfile: services/api/Dockerfile }
    depends_on:
      postgres: { condition: service_healthy }
      redis: { condition: service_healthy }
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/taskscheduler

  scheduler:
    build: { context: ., dockerfile: services/scheduler/Dockerfile }
    depends_on:
      postgres: { condition: service_healthy }
      rabbitmq: { condition: service_healthy }

  worker:
    build: { context: ., dockerfile: services/worker/Dockerfile }
    depends_on:
      postgres: { condition: service_healthy }
      rabbitmq: { condition: service_healthy }
    deploy:
      replicas: 3

  prometheus:
    image: prom/prometheus
    volumes:
      - ./monitoring/prometheus.yml:/etc/prometheus/prometheus.yml

  grafana:
    image: grafana/grafana
    volumes:
      - ./monitoring/grafana/dashboards:/etc/grafana/provisioning/dashboards
      - ./monitoring/grafana/datasources:/etc/grafana/provisioning/datasources
```

### Key Docker Design Decisions

| Decision | Rationale |
|---|---|
| Multi-stage builds | Smaller runtime images (JRE only, no JDK/source) |
| Health checks on all infra services | `depends_on: service_healthy` prevents app startup before infra is ready |
| Named volumes for PostgreSQL | Data survives `docker compose down` (without `-v`) |
| Separate service per component | API, scheduler, worker are independent processes — true distributed architecture |
| Worker `replicas: 3` | Demonstrates horizontal scaling out of the box |

---

## 25. Kubernetes Architecture

> **Prerequisite**: Kubernetes deployment is introduced only after the system works correctly with Docker Compose.

### Resource Layout

```
k8s/
├── base/
│   ├── namespace.yaml
│   ├── api-deployment.yaml
│   ├── api-service.yaml
│   ├── scheduler-deployment.yaml
│   ├── worker-deployment.yaml
│   ├── postgres-statefulset.yaml
│   ├── postgres-service.yaml
│   ├── rabbitmq-statefulset.yaml
│   ├── rabbitmq-service.yaml
│   ├── redis-deployment.yaml
│   ├── redis-service.yaml
│   ├── configmap.yaml
│   └── secrets.yaml
│
└── helm/
    └── task-scheduler/
        ├── Chart.yaml
        ├── values.yaml
        └── templates/
            ├── api-deployment.yaml
            ├── scheduler-deployment.yaml
            ├── worker-deployment.yaml
            └── ...
```

### Replica Strategy

| Component | Replicas | Scaling | Reason |
|---|---|---|---|
| API | 3 | HPA (CPU-based) | Stateless, load-balanced |
| Scheduler | 2 | Fixed | `SKIP LOCKED` partitions work; more than 2 is unnecessary at this scale |
| Worker | 3–10 | HPA (queue-depth-based) | Primary scaling lever |
| PostgreSQL | 1 (with PVC) | Manual | Stateful; HA via managed DB in production |
| RabbitMQ | 1 (with PVC) | Manual | Stateful; cluster mode for HA in production |
| Redis | 1 | Fixed | Ephemeral state; loss is tolerable |

### Health Probes

```yaml
# API Deployment
livenessProbe:
  httpGet: { path: /actuator/health/liveness, port: 8080 }
  initialDelaySeconds: 30
  periodSeconds: 10

readinessProbe:
  httpGet: { path: /actuator/health/readiness, port: 8080 }
  initialDelaySeconds: 10
  periodSeconds: 5
```

- **Liveness**: "Is the process alive?" Failure → K8s restarts the pod.
- **Readiness**: "Can this pod accept traffic?" Failure → K8s removes from Service endpoints.

The scheduler and worker have custom readiness that checks DB and RabbitMQ connectivity.

### Helm Values (Parameterized)

```yaml
api:
  replicas: 3
  resources:
    requests: { cpu: 250m, memory: 512Mi }
    limits: { cpu: 500m, memory: 1Gi }

worker:
  replicas: 5
  resources:
    requests: { cpu: 500m, memory: 512Mi }
    limits: { cpu: 1000m, memory: 1Gi }

scheduler:
  replicas: 2
  pollIntervalSeconds: 5
  batchSize: 50
```

---

## 26. Project Folder Structure

```
distributed-task-scheduler/
│
├── services/
│   ├── api/                          # API Service (Spring Boot)
│   │   ├── src/main/java/com/scheduler/api/
│   │   │   ├── controller/           # REST controllers
│   │   │   ├── dto/                  # Request/Response DTOs
│   │   │   ├── service/             # Business logic
│   │   │   ├── validation/          # Input validators
│   │   │   └── config/             # API-specific config
│   │   ├── src/main/resources/
│   │   │   └── application.yml
│   │   ├── src/test/
│   │   ├── Dockerfile
│   │   └── pom.xml
│   │
│   ├── scheduler/                    # Scheduler Service (Spring Boot)
│   │   ├── src/main/java/com/scheduler/scheduler/
│   │   │   ├── polling/             # Poll loop, batch claiming
│   │   │   ├── dispatch/           # RabbitMQ publishing
│   │   │   ├── recovery/           # Lease expiry & stale-task recovery
│   │   │   └── config/
│   │   ├── src/main/resources/
│   │   ├── src/test/
│   │   ├── Dockerfile
│   │   └── pom.xml
│   │
│   └── worker/                       # Worker Service (Spring Boot)
│       ├── src/main/java/com/scheduler/worker/
│       │   ├── consumer/            # RabbitMQ message listener
│       │   ├── execution/          # Task execution orchestration
│       │   ├── handler/            # TaskHandler implementations
│       │   │   ├── EmailTaskHandler.java
│       │   │   ├── HttpTaskHandler.java
│       │   │   └── DemoTaskHandler.java
│       │   ├── lease/              # Lease renewal service
│       │   ├── shutdown/           # Graceful shutdown
│       │   └── config/
│       ├── src/main/resources/
│       ├── src/test/
│       ├── Dockerfile
│       └── pom.xml
│
├── shared/                            # Shared Library (Maven module)
│   ├── src/main/java/com/scheduler/shared/
│   │   ├── model/                   # Task entity, enums (TaskStatus, Priority)
│   │   ├── repository/             # JPA repositories, native queries
│   │   ├── statemachine/           # State transition logic
│   │   ├── retry/                  # Backoff calculator
│   │   ├── messaging/             # RabbitMQ message DTOs
│   │   ├── config/                # Shared Spring config
│   │   └── util/                  # Common utilities
│   └── pom.xml
│
├── database/
│   └── migration/                    # Flyway SQL migrations
│       ├── V1__create_tasks_table.sql
│       ├── V2__create_task_attempts_table.sql
│       └── V3__add_indexes.sql
│
├── monitoring/
│   ├── prometheus.yml                # Prometheus scrape config
│   └── grafana/
│       ├── dashboards/              # JSON dashboard definitions
│       └── datasources/            # Prometheus datasource config
│
├── k8s/
│   ├── base/                        # Raw K8s manifests
│   └── helm/
│       └── task-scheduler/          # Helm chart
│
├── loadtest/
│   ├── scripts/                     # k6 test scripts
│   └── results/                    # Benchmark results (actual data only)
│
├── docs/
│   ├── BLUEPRINT.md                 # This document
│   ├── BENCHMARKS.md                # Performance results (post-testing)
│   └── diagrams/                   # Architecture & sequence diagrams
│
├── scripts/
│   ├── setup.sh                     # Dev environment setup
│   └── seed-tasks.sh               # Test data generation
│
├── docker-compose.yml
├── docker-compose.override.yml       # Dev overrides (debug ports, etc.)
├── pom.xml                           # Parent POM (Maven multi-module)
├── .gitignore
├── .env.example                      # Template for environment variables
├── PROJECT_CONTEXT.md
├── TECH_STACK.md
└── README.md
```

### Maven Multi-Module Structure

```xml
<!-- Root pom.xml -->
<modules>
    <module>shared</module>
    <module>services/api</module>
    <module>services/scheduler</module>
    <module>services/worker</module>
</modules>
```

The `shared` module is a dependency of all three services. It contains the task entity, state machine, repository, and message DTOs. This avoids code duplication while keeping services independently deployable.

---

## 27. Development Roadmap

### Phase 1 — Architecture & Blueprint (Current Phase)

- [x] Define requirements
- [x] Design architecture
- [x] Define state machine
- [x] Design DB schema
- [x] Design API
- [ ] **Review and approve this blueprint**

### Phase 2 — Foundation

- [ ] Initialize Maven multi-module project
- [ ] Configure Spring Boot for all three services
- [ ] Set up Docker Compose (PostgreSQL, RabbitMQ, Redis)
- [ ] Flyway migrations: create `tasks` and `task_attempts` tables
- [ ] Shared module: Task entity, TaskStatus enum, Priority enum

### Phase 3 — API Service

- [ ] `POST /api/v1/tasks` — create task with validation
- [ ] `GET /api/v1/tasks/{id}` — get task by ID
- [ ] `GET /api/v1/tasks` — list with filters and pagination
- [ ] Error handling and consistent error responses
- [ ] Unit tests for validation and service layer
- [ ] Integration tests with Testcontainers (PostgreSQL)

### Phase 4 — State Machine & Core Domain

- [ ] Implement state transition logic with CAS enforcement
- [ ] Unit tests for all legal/illegal transitions
- [ ] `POST /tasks/{id}/cancel` endpoint
- [ ] Idempotency key handling on task creation

### Phase 5 — Scheduler

- [ ] Poll loop with `FOR UPDATE SKIP LOCKED`
- [ ] RabbitMQ publisher (commit-then-publish)
- [ ] Priority-ordered dispatch
- [ ] Integration tests: scheduler + PostgreSQL + RabbitMQ

### Phase 6 — Worker

- [ ] RabbitMQ consumer with manual ACK
- [ ] Lease acquisition (CAS: QUEUED → RUNNING)
- [ ] TaskHandler interface + DemoTaskHandler
- [ ] Execution with timeout
- [ ] Outcome recording (SUCCESS / RETRY_WAIT / DEAD_LETTER)
- [ ] Integration tests: worker + PostgreSQL + RabbitMQ

### Phase 7 — Retry & Recovery

- [ ] Exponential backoff with jitter
- [ ] RETRY_WAIT → SCHEDULED recovery sweep
- [ ] Lease-expiry recovery sweep
- [ ] Stale-QUEUED recovery sweep
- [ ] Failure tests: worker crash, scheduler crash, duplicate delivery

### Phase 8 — Advanced Features

- [ ] Recurring tasks (cron expression, next-run generation)
- [ ] EmailTaskHandler, HttpTaskHandler (webhook)
- [ ] Lease renewal for long-running tasks
- [ ] Manual retry endpoint (`POST /tasks/{id}/retry`)
- [ ] `DELETE /tasks/{id}` (soft-delete)

### Phase 9 — Observability

- [ ] Micrometer metrics on all services
- [ ] Structured JSON logging (Logback)
- [ ] Prometheus scrape configuration
- [ ] Grafana dashboards (provisioned via JSON)
- [ ] OpenTelemetry tracing integration

### Phase 10 — Security & Hardening

- [ ] API key authentication
- [ ] Rate limiting (Redis-backed)
- [ ] Input validation hardening
- [ ] Graceful shutdown for all services
- [ ] OpenAPI documentation (springdoc-openapi)

### Phase 11 — Docker & CI/CD

- [ ] Multi-stage Dockerfiles for each service
- [ ] Docker Compose with health checks
- [ ] GitHub Actions pipeline: compile → test → build image
- [ ] `.env.example` and secrets documentation

### Phase 12 — Load Testing & Benchmarking

- [ ] k6 test scripts for all scenarios
- [ ] Run benchmarks at Tier 1–4 workloads
- [ ] Document actual results in `docs/BENCHMARKS.md`
- [ ] Grafana screenshots of dashboard under load

### Phase 13 — Kubernetes

- [ ] K8s manifests (Deployments, Services, ConfigMaps)
- [ ] Helm chart with parameterized values
- [ ] Health probes (liveness, readiness)
- [ ] Demonstrate horizontal scaling (adjust worker replicas)

### Phase 14 — Documentation & Polish

- [ ] Final README with architecture diagrams
- [ ] Sequence diagrams for key flows
- [ ] Known limitations documented
- [ ] Interview preparation notes

---

## 28. Technology Choices with Alternatives and Trade-offs

### Java 21

| Aspect | Details |
|---|---|
| **What** | Primary programming language (LTS release) |
| **Why** | Mature backend ecosystem, strong concurrency, excellent Spring Boot support, interview relevance |
| **How** | Virtual threads for I/O-bound task execution; `java.time` for scheduling; `ExecutorService` for thread pools |
| **Alternatives** | Go (simpler concurrency, but less enterprise ecosystem), Kotlin (nicer syntax, same JVM), Python (simpler but weaker concurrency/type safety) |
| **Trade-offs** | More verbose than Go/Kotlin; JVM memory overhead; but strong tooling and Spring ecosystem |
| **Failure scenarios** | JVM crash → container restart; OOM → investigate heap settings |
| **Testing** | JUnit 5, Mockito, JaCoCo |

### Spring Boot

| Aspect | Details |
|---|---|
| **What** | Backend application framework |
| **Why** | Dependency injection, auto-configuration, Spring Data JPA, Spring AMQP, Actuator health checks, Micrometer metrics |
| **How** | One Spring Boot application per service (API, scheduler, worker) |
| **Alternatives** | Quarkus (faster startup, GraalVM native), Micronaut (compile-time DI), plain Java (no framework overhead) |
| **Trade-offs** | Heavier than Quarkus/Micronaut at startup; but most mature ecosystem, best documentation, widest community |
| **Failure scenarios** | Misconfigured auto-wiring → startup failure (caught early); dependency version conflicts → Maven enforcer |
| **Testing** | `@SpringBootTest`, `@DataJpaTest`, `@WebMvcTest` |

### PostgreSQL

| Aspect | Details |
|---|---|
| **What** | Relational database — single source of truth for task state |
| **Why** | ACID transactions, row-level locking, `FOR UPDATE SKIP LOCKED`, constraints, mature indexing, durable persistence |
| **How** | Stores all task metadata, state transitions, attempt history |
| **Alternatives** | MySQL (similar, but `SKIP LOCKED` support is less mature), MongoDB (flexible schema, but weaker transactional guarantees for state machines), DynamoDB (managed, but no row-level locking semantics) |
| **Trade-offs** | Single-node bottleneck at extreme scale; but vertically scalable and sufficient for 100K tasks/day. Sharding via Citus if needed later. |
| **Failure scenarios** | Connection pool exhaustion → 503; disk full → alerts via monitoring; corruption → point-in-time recovery from WAL |
| **Testing** | Testcontainers with real PostgreSQL |

### RabbitMQ

| Aspect | Details |
|---|---|
| **What** | Message broker for asynchronous task dispatch |
| **Why** | Decouples scheduler from workers; provides buffering, backpressure, consumer scaling, ACK/NACK, dead-lettering |
| **How** | Direct exchange with priority-based routing keys; workers consume with manual ACK |
| **Alternatives** | Kafka (log-based, better for event streaming/replay, but overkill for task queuing); Redis Streams (simpler, but less mature DLQ/routing); SQS (managed, but cloud-locked) |
| **Trade-offs** | Additional infrastructure to operate; messages are not the source of truth (DB is). But provides push-based delivery that polling cannot match. |
| **Failure scenarios** | Broker down → scheduler retries; message lost → stale-task recovery; consumer crash → RabbitMQ redelivers |
| **Testing** | Testcontainers with real RabbitMQ |

### Redis

| Aspect | Details |
|---|---|
| **What** | In-memory data store for ephemeral state |
| **Why** | API rate limiting (high-frequency counter), optional distributed lock for scheduler coordination |
| **How** | `INCR` + `EXPIRE` for rate limiting; `SET NX EX` for optional locks |
| **Alternatives** | In-memory rate limiting (loses state on restart, not shared across API instances); PostgreSQL for rate limiting (works but slower for high-frequency checks); Bucket4j (in-process, not distributed) |
| **Trade-offs** | Additional dependency; but Redis is lightweight and the failure mode is graceful degradation (rate limiting fails open) |
| **Failure scenarios** | Redis down → rate limiting disabled (fail-open); data loss → acceptable (ephemeral state only) |
| **Testing** | Testcontainers with real Redis |

### Flyway

| Aspect | Details |
|---|---|
| **What** | Database migration tool |
| **Why** | Version-controlled, reproducible schema from scratch |
| **Alternatives** | Liquibase (XML/YAML migrations, more features but more complex); manual SQL scripts (error-prone, no version tracking) |
| **Trade-offs** | SQL-only migrations (no rollback generation). Acceptable — we don't plan rollback migrations. |
| **Testing** | Every `@SpringBootTest` applies migrations to a fresh Testcontainers DB |

### Maven

| Aspect | Details |
|---|---|
| **What** | Build system |
| **Why** | Standard for Spring Boot; multi-module support; Maven Wrapper for reproducibility |
| **Alternatives** | Gradle (faster builds, more flexible, but steeper learning curve and less predictable for multi-module) |
| **Trade-offs** | Verbose XML; slower than Gradle on incremental builds. But more predictable and better documented for Spring Boot projects. |

### k6

| Aspect | Details |
|---|---|
| **What** | Load testing tool |
| **Why** | Modern, scriptable in JavaScript, produces structured metrics, integrates with Prometheus/Grafana |
| **Alternatives** | JMeter (GUI-based, more features, but harder to version-control); Gatling (Scala-based, powerful but steeper learning curve); wrk (simple but limited scripting) |
| **Trade-offs** | JavaScript scripting (not Java) — acceptable since tests are small scripts, not application code |

---

## 29. Expected Interview Questions

### Basic

| Question | Key Points |
|---|---|
| What problem does this system solve? | Reliable deferred/recurring task execution that survives component failures |
| Why is it distributed? | Independent components (API, scheduler, workers) communicate over network; each can fail/scale independently |
| Why use a message queue? | Decouples scheduling from execution; provides buffering, backpressure, horizontal scaling |
| Why not execute tasks directly from the API? | API should return fast; long-running execution blocks threads; no buffering or worker scaling |

### Intermediate

| Question | Key Points |
|---|---|
| How does task scheduling work? | Scheduler polls DB for due tasks, claims via `FOR UPDATE SKIP LOCKED`, publishes to queue |
| How do you prevent duplicate scheduling? | `SKIP LOCKED` ensures concurrent schedulers claim disjoint task sets |
| How do retries work? | Exponential backoff with jitter; `RETRY_WAIT` state with `next_retry_at`; scheduler re-dispatches when time arrives |
| What happens if a worker crashes? | Lease expires → recovery sweep detects stale RUNNING task → task re-enters queue |
| What is idempotency? | Same operation applied multiple times produces same result. Task has `idempotency_key`; worker checks state before executing |

### Advanced

| Question | Key Points |
|---|---|
| Why can't you guarantee exactly-once execution? | Worker can execute side effect, crash before recording success → system retries → duplicate. Fundamental distributed systems limitation with external effects. |
| How do multiple schedulers coordinate? | `FOR UPDATE SKIP LOCKED` — no distributed lock needed. Each scheduler locks different rows. |
| What race conditions exist? | Concurrent claim (solved by CAS); concurrent cancel+execute (CAS ensures one wins); duplicate message (worker checks DB state) |
| What isolation level do you use? | `READ COMMITTED` — sufficient because critical operations use explicit row locks (`FOR UPDATE`). Serializable would reduce throughput. |
| How does backpressure work? | Prefetch limit on workers; if workers are full, queue grows; queue depth monitored; alerts on threshold |
| What becomes the bottleneck? | At scale: PostgreSQL (scheduler poll queries, worker state updates). Mitigation: batch operations, read replicas, partitioning. |
| How would you handle clock skew? | `scheduled_at` comparisons use DB `now()`, not application clock. All timestamps are UTC. |
| How would you scale to millions of tasks? | Partition tasks by hash; multiple scheduler groups; read replicas; possibly Citus for PostgreSQL sharding |

---

## 30. Known Limitations

| Limitation | Why It Exists | Mitigation |
|---|---|---|
| **Not exactly-once** | Fundamental distributed systems constraint with external side effects | Idempotency keys; documented as at-least-once |
| **Scheduling latency bounded by poll interval** | Polling architecture; event-driven would be lower latency | Configurable interval; LISTEN/NOTIFY as future optimization |
| **PostgreSQL is the throughput bottleneck** | All state transitions go through a single DB | Sufficient for 100K tasks/day; shard or partition for higher scale |
| **No task execution ordering guarantee** | Concurrent workers + queue = non-deterministic order | Priority queues provide approximate ordering; strict ordering not supported |
| **No task dependencies / DAGs** | Not in scope for this phase | Future improvement |
| **Single-tenant** | No namespace isolation | Future improvement if multi-tenancy needed |
| **Basic authentication (API key)** | Not production-grade auth | Spring Security + OAuth2/JWT as future improvement |
| **Redis is optional but recommended** | Rate limiting degrades without it | In-memory fallback for single-instance deployments |
| **No task payload encryption** | Payloads stored in plaintext JSONB | Encrypt sensitive fields at application level if needed |

---

## 31. Future Improvements

| Improvement | Value | Complexity |
|---|---|---|
| **Task DAGs / dependencies** | Execute Task B only after Task A succeeds | High — requires dependency graph, topological ordering |
| **LISTEN/NOTIFY for instant dispatch** | Sub-second scheduling latency for immediate tasks | Medium — PostgreSQL trigger on INSERT |
| **Citus/partitioning for PostgreSQL** | Scale beyond single-node DB | High — requires partition key strategy |
| **Multi-tenancy with namespaces** | Shared infrastructure, isolated task sets | Medium — add `tenant_id` column, partition queues |
| **OAuth2/JWT authentication** | Production-grade API security | Medium — Spring Security integration |
| **mTLS for internal communication** | Zero-trust internal network | Medium — certificate management |
| **Task result storage** | Return execution output to the caller | Low — add `result` JSONB column |
| **Webhook delivery with retry** | Notify external services of task completion | Medium — separate notification subsystem |
| **Admin UI** | Dashboard for task management (cancel, retry, inspect) | Medium — React/Vue frontend |
| **Prometheus alerting rules** | Automated alerts for queue depth, failure rate, latency | Low — PrometheusRule YAML |
| **Blue-green / canary deployments** | Zero-downtime deployment of worker code changes | Medium — K8s rolling update strategy |

---

> **Next step**: Review this blueprint. If any section is unclear, any decision feels wrong, or any requirement is missing, let me know. Once approved, we begin Phase 2 — project foundation.
