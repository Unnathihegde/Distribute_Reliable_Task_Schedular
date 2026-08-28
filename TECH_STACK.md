# TECHNOLOGY STACK — DISTRIBUTED RELIABLE TASK SCHEDULER

This project is a **major Computer Science engineering project** focused on backend engineering, distributed systems, reliability, fault tolerance, and DevOps.

The technology stack is intentionally selected to allow us to demonstrate real engineering concepts while keeping the architecture understandable and defensible.

---

## 1. PRIMARY PROGRAMMING LANGUAGE

### Java 21 LTS

Java 21 is the primary programming language.

Why:

* Mature backend ecosystem
* Strong concurrency primitives
* Excellent Spring Boot support
* Strong transaction/database ecosystem
* Production-grade tooling
* Widely used in enterprise/backend systems
* Good interview relevance

Important Java concepts we should understand and use where appropriate:

* Threads
* `ExecutorService`
* Thread pools
* `CompletableFuture`
* Synchronization
* Locks
* Concurrent collections
* Atomic operations
* `java.time`
* Exception handling
* Graceful shutdown
* Virtual threads where appropriate and justified

Do not use advanced Java features simply because they exist. Every choice should have a reason.

---

# 2. BACKEND FRAMEWORK

## Spring Boot

Spring Boot will be used for the backend services.

Primary responsibilities:

* REST APIs
* Dependency injection
* Configuration
* Database integration
* Transaction management
* Validation
* Application lifecycle
* Health checks
* Metrics integration

We will use Spring Boot for the API and, where appropriate, the scheduler/worker services.

However, the architecture must still keep the major components logically separated.

---

# 3. API

## Spring Web / REST

The API will expose REST endpoints.

Example:

```text
POST   /api/v1/tasks
GET    /api/v1/tasks/{id}
GET    /api/v1/tasks
POST   /api/v1/tasks/{id}/cancel
POST   /api/v1/tasks/{id}/retry
DELETE /api/v1/tasks/{id}
```

Operational endpoints:

```text
GET /actuator/health
GET /actuator/ready
GET /actuator/metrics
```

API requirements:

* Request validation
* Proper HTTP status codes
* Consistent error responses
* Idempotency support
* Pagination where required
* API versioning
* OpenAPI documentation

---

# 4. DATABASE

## PostgreSQL

PostgreSQL is the **durable source of truth** for task metadata and lifecycle state.

It will store information such as:

```text
task_id
task_type
payload
status

created_at
scheduled_at
started_at
completed_at

attempt_count
max_attempts

priority

lease_id
lease_expires_at

idempotency_key

last_error
updated_at
```

For recurring tasks:

```text
cron_expression
next_run_at
recurrence_enabled
```

PostgreSQL is important because we need:

* ACID transactions
* Row-level locking
* Atomic state transitions
* Constraints
* Indexes
* Durable persistence
* Reliable recovery

Important concepts we must understand:

* Transactions
* Isolation levels
* Row locks
* `SELECT ... FOR UPDATE`
* `SKIP LOCKED`
* Atomic `UPDATE`
* Indexing
* Connection pooling

---

# 5. DATABASE ACCESS

## Spring Data JPA / Hibernate

Use JPA/Hibernate where it makes the application clearer and maintainable.

However, do not blindly use ORM abstractions for every operation.

For concurrency-critical scheduler operations, explicit SQL may be preferable when it provides better control over:

* locking
* claiming
* atomic state transitions
* performance

The project should demonstrate that we understand both ORM abstractions and the underlying SQL/database behavior.

---

# 6. DATABASE MIGRATIONS

## Flyway

Use Flyway for version-controlled database schema migrations.

Example:

```text
V1__create_tasks_table.sql
V2__add_idempotency_key.sql
V3__add_task_indexes.sql
```

The database schema must be reproducible from a fresh environment.

---

# 7. MESSAGE BROKER

## RabbitMQ

RabbitMQ will be the primary asynchronous task/message broker.

Architecture:

```text
Scheduler
    |
    v
RabbitMQ
    |
    v
Worker Pool
```

RabbitMQ provides:

* Queueing
* Producer/consumer model
* Acknowledgements
* Redelivery
* Routing
* Dead-lettering
* Backpressure
* Consumer scaling

Important concepts to understand:

* Exchanges
* Queues
* Bindings
* Routing keys
* Publishers
* Consumers
* Acknowledgements
* Negative acknowledgements
* Prefetch
* Redelivery
* Dead-letter exchanges
* Consumer failures

Do not treat RabbitMQ as the source of truth.

PostgreSQL remains the authoritative task-state store.

---

# 8. REDIS

## Redis

Redis is an **optional coordination/ephemeral-state component**, not the primary database.

Potential responsibilities:

* Distributed coordination
* Short-lived locks where justified
* Rate limiting
* Ephemeral state
* Caching where useful

We must not introduce Redis just because "distributed systems use Redis."

Every Redis use case must have a clear justification.

If PostgreSQL can safely solve a problem with fewer moving parts, PostgreSQL may be preferable.

---

# 9. SCHEDULER SERVICE

The scheduler will be implemented in Java/Spring Boot.

Responsibilities:

```text
Find tasks that are due
        ↓
Safely claim task
        ↓
Publish task to RabbitMQ
        ↓
Update task state
```

Potential architecture:

```text
Scheduler 1
Scheduler 2
Scheduler 3
```

Multiple scheduler instances must not incorrectly dispatch the same task.

We will investigate and implement appropriate concurrency control using mechanisms such as:

* PostgreSQL atomic state transitions
* Row-level locking
* `FOR UPDATE SKIP LOCKED`
* Leases
* Redis coordination only where genuinely necessary

The design must explicitly discuss the trade-offs.

---

# 10. WORKER SERVICE

Workers will also be implemented in Java/Spring Boot.

Responsibilities:

```text
Consume task
    ↓
Validate task
    ↓
Claim/mark RUNNING
    ↓
Execute task
    ↓
SUCCESS / RETRY / FAILED
```

Workers should support:

* Concurrent execution
* Configurable concurrency
* Timeouts
* Graceful shutdown
* Error handling
* Retry handling
* Idempotency
* Heartbeats/leases where necessary

We should understand how Java thread pools interact with RabbitMQ consumers.

---

# 11. TASK EXECUTION MODEL

The initial project should support a controlled set of task types.

For example:

```text
EMAIL
HTTP
DEMO
```

We should NOT allow arbitrary unsafe code execution.

The task type determines which handler executes the task.

Conceptually:

```text
Task
  |
  +---- EMAIL  -> EmailTaskHandler
  |
  +---- HTTP   -> HttpTaskHandler
  |
  +---- DEMO   -> DemoTaskHandler
```

This makes the system extensible without executing arbitrary user code.

---

# 12. TASK RELIABILITY MODEL

The system should use:

## At-least-once processing

We should NOT claim universal exactly-once execution.

Failure scenario:

```text
Worker executes task
      ↓
External side effect succeeds
      ↓
Worker crashes before recording SUCCESS
      ↓
Task becomes eligible for retry
      ↓
Task executes again
```

Therefore:

```text
Delivery/processing:
AT-LEAST-ONCE

Application-level duplicate protection:
IDEMPOTENCY
```

This distinction is critical.

---

# 13. IDEMPOTENCY

Tasks should support an idempotency key.

Example:

```text
idempotency_key = "payment-12345"
```

The system should detect duplicate logical requests where appropriate.

We should clearly distinguish:

```text
Duplicate message
        ≠
Duplicate business operation
```

The task infrastructure should make duplicate delivery safe where the operation supports idempotency.

---

# 14. RETRY SYSTEM

Retries will be configurable.

Example:

```text
max_attempts = 5
```

Use exponential backoff with jitter.

Conceptually:

```text
delay = base_delay × 2^attempt + jitter
```

Example:

```text
Attempt 1 → failure
Attempt 2 → wait
Attempt 3 → wait longer
Attempt 4 → wait longer
Attempt 5 → final attempt
```

After retry exhaustion:

```text
FAILED
   ↓
DEAD_LETTER
```

Avoid infinite retries.

---

# 15. DEAD-LETTER HANDLING

Tasks that repeatedly fail should be isolated.

Store:

```text
task_id
attempt_count
last_error
failure_timestamp
```

The system should allow operators to inspect failed tasks and potentially manually retry them.

RabbitMQ dead-lettering and application-level task state should be designed together rather than assuming the broker alone solves failure management.

---

# 16. WORKER LEASE / CRASH RECOVERY

A worker crash must not leave tasks permanently stuck in:

```text
RUNNING
```

Use a lease mechanism such as:

```text
lease_expires_at
```

Example:

```text
Task
 ↓
RUNNING
 ↓
lease expires
 ↓
Recovery process detects stale task
 ↓
Task becomes retryable
```

Long-running workers may need lease renewal/heartbeat behavior.

---

# 17. OBSERVABILITY

The project must have production-style observability.

### Metrics

Use:

## Micrometer

Application metrics will be exposed through Micrometer.

Metrics can include:

```text
tasks_submitted_total
tasks_completed_total
tasks_failed_total
tasks_retried_total
tasks_dead_lettered_total

task_execution_duration
task_queue_wait_duration

queue_depth
active_workers

scheduler_claim_rate
task_success_rate
```

---

# 18. PROMETHEUS

Use Prometheus for metrics collection.

Architecture:

```text
Spring Boot
    ↓
Micrometer
    ↓
Prometheus
```

Prometheus will scrape application metrics.

---

# 19. GRAFANA

Use Grafana to visualize:

* Task throughput
* Success/failure rate
* Retry rate
* Queue depth
* Worker utilization
* Execution latency
* Scheduling latency
* Database-related metrics where available

Example dashboard:

```text
Tasks/sec
Queue depth
Active workers
p50 latency
p95 latency
p99 latency
Failure rate
Retry rate
```

---

# 20. LOGGING

Use structured logging through Java/Spring's logging ecosystem.

Every important event should contain useful context.

Example fields:

```text
timestamp
level
service
task_id
worker_id
attempt
event
error
duration
```

Example conceptual log:

```json
{
  "event": "task_execution_failed",
  "task_id": "abc123",
  "worker_id": "worker-2",
  "attempt": 3,
  "error": "connection timeout"
}
```

Logs should make distributed debugging possible.

---

# 21. DISTRIBUTED TRACING

Use:

## OpenTelemetry

Trace a task through:

```text
API
 ↓
PostgreSQL
 ↓
Scheduler
 ↓
RabbitMQ
 ↓
Worker
 ↓
External service
```

Use task/correlation identifiers where appropriate.

The objective is to understand where latency and failures occur.

---

# 22. TESTING

Testing is a major part of this project.

### Unit testing

Use:

## JUnit 5

Test:

* State transitions
* Retry calculations
* Validation
* Scheduling logic
* Idempotency
* Failure handling

---

### Integration testing

Use:

## Testcontainers

Run real temporary containers for:

```text
PostgreSQL
RabbitMQ
Redis
```

This allows us to test the real infrastructure rather than mocking everything.

---

### End-to-end testing

Test:

```text
POST /tasks
      ↓
PostgreSQL
      ↓
Scheduler
      ↓
RabbitMQ
      ↓
Worker
      ↓
Execution
      ↓
SUCCESS
```

---

# 23. FAILURE TESTING

Deliberately simulate:

```text
Worker crash
Scheduler crash
Duplicate task
Duplicate message
Database connection failure
RabbitMQ failure
Long-running task
Worker timeout
Container restart
Network delay
Retry exhaustion
```

The goal is to prove the reliability mechanisms actually work.

---

# 24. LOAD TESTING

Use:

## k6

Measure actual:

```text
requests/sec
tasks/sec
queue throughput
worker throughput

p50 latency
p95 latency
p99 latency

failure rate
retry rate
recovery time
```

Test increasing workloads such as:

```text
100 tasks
1,000 tasks
10,000 tasks
100,000 tasks
```

Do not invent performance numbers.

Only actual benchmark results may appear on the resume.

---

# 25. CONTAINERIZATION

Use:

## Docker

Each major application component should have its own container.

Potential services:

```text
api
scheduler
worker
postgres
rabbitmq
redis
prometheus
grafana
```

---

# 26. LOCAL ORCHESTRATION

Use:

## Docker Compose

The complete local environment should eventually start with:

```bash
docker compose up
```

This should provide a reproducible development environment.

---

# 27. KUBERNETES

Kubernetes will be introduced only after the system works correctly with Docker Compose.

Use Kubernetes to demonstrate:

* Service deployment
* Multiple replicas
* Health checks
* Readiness probes
* Liveness probes
* Configuration
* Secrets
* Horizontal scaling
* Failure recovery

Example:

```text
API
  × 3 replicas

Scheduler
  × 2 replicas

Worker
  × 10 replicas
```

---

# 28. HELM

Use Helm to package Kubernetes deployment configuration.

This allows us to parameterize things such as:

```text
replica counts
image versions
resource limits
environment configuration
```

---

# 29. CI/CD

Use:

## GitHub Actions

Pipeline should eventually perform:

```text
Push
 ↓
Compile
 ↓
Unit tests
 ↓
Lint/static analysis
 ↓
Integration tests
 ↓
Build Docker image
 ↓
(optional) publish image
```

Deployment automation can be added after the core pipeline is stable.

---

# 30. CODE QUALITY

Use appropriate Java tooling such as:

* Checkstyle
* SpotBugs
* JaCoCo
* Maven
* Spring Boot validation

The exact tools should be selected based on value rather than adding every available tool.

---

# 31. BUILD SYSTEM

Use:

## Maven

The project should use the Maven Wrapper.

Therefore developers can build with:

```bash
./mvnw clean verify
```

On Windows:

```powershell
.\mvnw.cmd clean verify
```

Avoid depending on a globally installed Maven version.

---

# 32. API DOCUMENTATION

Use:

## OpenAPI / Swagger

Document:

* Endpoints
* Request schemas
* Response schemas
* Error responses
* Authentication if implemented
* Idempotency behavior

---

# 33. VERSION CONTROL

Use:

## Git + GitHub

Use meaningful commits such as:

```text
feat: add task creation API
feat: implement task state machine
feat: add scheduler polling
feat: integrate RabbitMQ
feat: implement worker execution
feat: add retry policy
test: add worker crash recovery
feat: add task metrics
docs: document scheduler concurrency model
```

---

# 34. FINAL STACK SUMMARY

```text
┌─────────────────────────────────────────────┐
│                  CLIENT                     │
└──────────────────────┬──────────────────────┘
                       │
                       ▼
              ┌─────────────────┐
              │ Java 21         │
              │ Spring Boot     │
              │ REST API        │
              └────────┬────────┘
                       │
              ┌────────┴─────────┐
              ▼                  ▼
       ┌──────────────┐    ┌──────────────┐
       │ PostgreSQL   │    │    Redis     │
       │ Source of    │    │ Coordination │
       │ Truth        │    │ / Ephemeral  │
       └──────┬───────┘    └──────────────┘
              │
              ▼
       ┌──────────────────┐
       │ Java Scheduler   │
       │ Spring Boot      │
       └────────┬─────────┘
                │
                ▼
       ┌──────────────────┐
       │    RabbitMQ      │
       │   Task Queue     │
       └────────┬─────────┘
                │
       ┌────────┼────────┐
       ▼        ▼        ▼
   ┌────────┐┌────────┐┌────────┐
   │Worker 1││Worker 2││Worker N│
   │ Java   ││ Java   ││ Java   │
   └────┬───┘└────┬───┘└────┬───┘
        │          │          │
        └──────────┼──────────┘
                   ▼
             Task Execution


Observability:

Java/Spring
     │
     ├── Micrometer → Prometheus → Grafana
     │
     ├── Structured Logs
     │
     └── OpenTelemetry → Distributed Tracing


Infrastructure:

Docker
   ↓
Docker Compose
   ↓
Kubernetes
   ↓
Helm


Development:

Git
 ↓
GitHub
 ↓
GitHub Actions
```

---

# 35. TECHNOLOGY DECISION RULE

The architecture should not become complex merely for appearance.

For every technology, answer:

1. What problem does it solve?
2. Why is it needed?
3. Why was this technology selected?
4. What alternatives were considered?
5. What are the trade-offs?
6. What happens if it fails?
7. How do we test it?

If a technology is not necessary, do not introduce it.

The final project should demonstrate **engineering judgment**, not technology accumulation.
