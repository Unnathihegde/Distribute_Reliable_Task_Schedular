# MASTER PROJECT CONTEXT — DISTRIBUTED RELIABLE TASK SCHEDULER

You are helping me build a **major Computer Science engineering project** called:

# Distributed Reliable Task Scheduler

This is not a simple CRUD application and not a toy scheduler.

I want to build a **production-style distributed task scheduling system** that demonstrates real backend/distributed-systems engineering concepts such as:

* Distributed systems
* Task scheduling
* Asynchronous processing
* Message queues
* Worker pools
* Fault tolerance
* Reliability
* Retries
* Idempotency
* Dead-letter queues
* Persistent task state
* Concurrency control
* Distributed locking
* Leader election / scheduler coordination
* Horizontal scalability
* Failure recovery
* Observability
* Metrics
* Logging
* Tracing
* Docker
* Kubernetes concepts
* API design
* Database design
* Testing
* Load testing
* System design

The project should be strong enough to discuss in:

* Major project viva
* Technical interviews
* Backend interviews
* Distributed systems interviews
* DevOps interviews
* Resume/project discussions

Do NOT treat this as a basic student project.

The implementation should be realistic, explainable, measurable, and technically defensible.

---

# 1. CORE PROBLEM

We want to build a system where users/services can submit tasks that should execute:

* Immediately
* At a specified future time
* Repeatedly according to a schedule

For example:

```text
POST /tasks

{
  "type": "send_email",
  "payload": {
    "to": "user@example.com",
    "subject": "Reminder"
  },
  "schedule_at": "2026-08-30T10:00:00Z"
}
```

The system should persist the task and eventually execute it using a worker.

The important part is that the system must remain reliable even when individual components fail.

For example:

```text
Client
   |
   v
API Server
   |
   v
Task Database
   |
   v
Scheduler
   |
   v
Queue
   |
   v
Workers
   |
   v
Task Execution
```

But this simplified diagram hides the actual distributed-systems problems.

We need to design the system so that failures such as:

* Scheduler crash
* Worker crash
* Database failure
* Queue failure
* Network failure
* Duplicate delivery
* Worker timeout
* Process restart
* Container restart
* Multiple scheduler instances
* Multiple workers processing simultaneously

do not cause unacceptable task loss or corruption.

---

# 2. THE MAIN DESIGN GOAL

The central goal is:

> Build a distributed task scheduling platform that reliably accepts, persists, schedules, dispatches, executes, retries, and tracks tasks even when individual components fail.

The system should prioritize:

### Durability

Once a task is accepted successfully, it should not simply disappear because a process crashes.

### Reliability

Temporary failures should result in retry/recovery rather than immediate permanent failure.

### Fault tolerance

The failure of one worker should not bring down the entire system.

### Horizontal scalability

We should be able to increase throughput by adding more workers/scheduler capacity.

### Observability

We should know:

* What happened to a task?
* Where is it currently?
* Why did it fail?
* How many retries occurred?
* How long did execution take?
* Is the queue backing up?
* Are workers healthy?

### Correctness

We must carefully reason about:

* Duplicate execution
* Race conditions
* Concurrent schedulers
* Concurrent workers
* Task state transitions
* Retry behavior
* Crash recovery

---

# 3. VERY IMPORTANT DISTRIBUTED-SYSTEM REALITY

Do NOT claim that this system provides magical "exactly once execution".

In a real distributed system, exactly-once execution is extremely difficult/impossible to guarantee in the general case when external side effects are involved.

For example:

```text
Worker
   |
   | execute payment
   v
External service

payment succeeds

BUT

worker crashes before recording SUCCESS
```

The system may later retry the task.

Therefore the correct model is closer to:

> At-least-once task delivery/execution with idempotency protection.

We should explicitly explain this in the documentation and interviews.

The system should aim for:

```text
Durable task acceptance
+
At-least-once processing
+
Retries
+
Idempotency
+
Failure recovery
```

rather than making an unrealistic exactly-once claim.

---

# 4. HIGH-LEVEL ARCHITECTURE

The system should consist of the following major components.

```text
                         ┌──────────────────┐
                         │      Client      │
                         └────────┬─────────┘
                                  │
                                  v
                         ┌──────────────────┐
                         │    API Server    │
                         └────────┬─────────┘
                                  │
                         ┌────────v─────────┐
                         │     Database     │
                         │                  │
                         │ Task Metadata    │
                         │ Task State       │
                         │ Retry Info       │
                         │ Scheduling Info  │
                         └────────┬─────────┘
                                  │
                                  v
                         ┌──────────────────┐
                         │    Scheduler     │
                         │                  │
                         │ Find due tasks   │
                         │ Claim tasks      │
                         │ Dispatch tasks   │
                         └────────┬─────────┘
                                  │
                                  v
                         ┌──────────────────┐
                         │   Message Queue  │
                         └───────┬──────────┘
                                 │
                    ┌────────────┼────────────┐
                    │            │            │
                    v            v            v
              ┌──────────┐ ┌──────────┐ ┌──────────┐
              │ Worker 1 │ │ Worker 2 │ │ Worker N │
              └────┬─────┘ └────┬─────┘ └────┬─────┘
                   │             │             │
                   └─────────────┼─────────────┘
                                 v
                         ┌──────────────────┐
                         │ Task Execution   │
                         └────────┬─────────┘
                                  │
                                  v
                         ┌──────────────────┐
                         │ Update Task State│
                         └──────────────────┘
```

This architecture must be refined during implementation rather than blindly copied.

---

# 5. TASK LIFECYCLE

Every task should have a clearly defined lifecycle.

A possible state machine is:

```text
CREATED
   |
   v
SCHEDULED
   |
   v
QUEUED
   |
   v
RUNNING
   |
   +-----------> SUCCESS
   |
   v
FAILED
   |
   v
RETRY_WAIT
   |
   v
QUEUED
```

After retry exhaustion:

```text
FAILED
   |
   v
DEAD_LETTER
```

Cancellation should also be supported:

```text
SCHEDULED ─────> CANCELLED
QUEUED    ─────> CANCELLED
```

We must define legal state transitions.

For example:

```text
SCHEDULED -> QUEUED
QUEUED -> RUNNING
RUNNING -> SUCCESS
RUNNING -> RETRY_WAIT
RUNNING -> FAILED
RETRY_WAIT -> QUEUED
FAILED -> DEAD_LETTER
```

We should prevent invalid transitions.

---

# 6. TASK DATA MODEL

A task should contain enough information to recover and understand its lifecycle.

Possible fields:

```text
id
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

worker_id

lease_id
lease_expires_at

last_error

idempotency_key

created_by

updated_at
```

For recurring tasks, we may additionally have:

```text
cron_expression
next_run_at
recurrence_enabled
```

The exact schema should be designed carefully before implementation.

---

# 7. API

The API should expose endpoints such as:

```text
POST   /tasks
GET    /tasks/{id}
GET    /tasks
POST   /tasks/{id}/cancel
POST   /tasks/{id}/retry
DELETE /tasks/{id}
```

Potential administrative endpoints:

```text
GET /health
GET /ready
GET /metrics
```

The API should validate:

* Task type
* Payload
* Schedule time
* Retry configuration
* Priority
* Idempotency key

It should return proper HTTP status codes.

---

# 8. SCHEDULER

The scheduler is one of the most important components.

Its job is NOT to execute tasks.

Its job is to identify tasks that are ready to run and safely dispatch them.

Conceptually:

```text
while true:

    find tasks where:
        status = SCHEDULED
        scheduled_at <= now

    safely claim task

    publish task to queue

    update task state
```

However, this creates an important race condition.

Imagine:

```text
Scheduler A finds Task X
Scheduler B finds Task X
```

Both could attempt to enqueue it.

Therefore we need concurrency control.

Possible mechanisms include:

* Database row locking
* Atomic UPDATE
* Compare-and-swap style state transition
* Lease-based claiming
* Distributed locks

We should prefer database-level atomicity where appropriate rather than adding unnecessary distributed locks.

---

# 9. MULTIPLE SCHEDULERS

The system should eventually support:

```text
Scheduler 1
Scheduler 2
Scheduler 3
```

This creates the problem:

> How do we prevent multiple schedulers from dispatching the same task?

Possible approaches:

### Approach A — Database row locking

Use a transaction with row-level locking.

Example conceptual operation:

```sql
SELECT ...
FOR UPDATE SKIP LOCKED
```

Then atomically transition:

```text
SCHEDULED -> QUEUED
```

### Approach B — Atomic state transition

For example:

```sql
UPDATE tasks
SET status = 'QUEUED'
WHERE id = ?
AND status = 'SCHEDULED'
```

Only one scheduler succeeds.

### Approach C — Distributed lock

Use Redis or another coordination mechanism.

But this should not be added simply because "distributed systems use Redis".

We should use the simplest mechanism that provides the required correctness.

---

# 10. MESSAGE QUEUE

The queue separates scheduling from execution.

This gives us:

```text
Scheduler
    |
    v
Queue
    |
    v
Workers
```

Benefits:

* Buffering
* Backpressure
* Worker decoupling
* Horizontal scaling
* Retry handling
* Failure isolation

If 10,000 tasks become ready simultaneously, the scheduler should not need to execute all of them.

Instead:

```text
Scheduler -> Queue -> Worker Pool
```

Workers consume tasks according to capacity.

---

# 11. WORKERS

Workers perform the actual task execution.

Conceptually:

```text
while true:

    task = consume()

    mark RUNNING

    execute(task)

    if success:
        mark SUCCESS

    else:
        retry or mark FAILED
```

Workers should have:

* Concurrency limits
* Graceful shutdown
* Timeouts
* Error handling
* Retry handling
* Heartbeats/leases where necessary
* Idempotency protection
* Structured logging

---

# 12. WORKER CRASH SCENARIO

This is a critical reliability case.

Suppose:

```text
Task X -> RUNNING
Worker 1 crashes
```

Without recovery, Task X may remain stuck forever.

Therefore we need a lease/visibility timeout concept.

Example:

```text
lease_expires_at = current_time + 60 seconds
```

If the worker crashes and does not renew/complete the task:

```text
lease expires
```

A recovery mechanism can detect:

```text
RUNNING
AND
lease_expires_at < now
```

and make the task eligible for retry.

This gives us failure recovery.

---

# 13. RETRIES

Transient failures should not immediately permanently fail a task.

Example:

```text
Attempt 1 -> FAIL
Attempt 2 -> FAIL
Attempt 3 -> SUCCESS
```

We should support configurable:

```text
max_attempts
```

and exponential backoff.

Example:

```text
1st retry: 1 sec
2nd retry: 2 sec
3rd retry: 4 sec
4th retry: 8 sec
```

A production implementation should also consider jitter to avoid synchronized retry storms.

Conceptually:

```text
delay = base_delay * 2^attempt + random_jitter
```

---

# 14. DEAD-LETTER QUEUE

If a task continuously fails:

```text
attempt 1 -> fail
attempt 2 -> fail
attempt 3 -> fail
...
attempt N -> fail
```

we should not retry forever.

Instead:

```text
FAILED
   |
   v
DEAD_LETTER
```

The dead-letter mechanism allows operators to inspect permanently failing tasks.

We should support:

* Reason for failure
* Number of attempts
* Last error
* Timestamp
* Manual retry

---

# 15. IDEMPOTENCY

This is extremely important.

Suppose:

```text
Worker executes payment
Payment succeeds
Worker crashes
```

The scheduler may retry.

The payment could happen twice unless the operation is idempotent.

Therefore the task should support an idempotency key such as:

```text
idempotency_key = "payment-12345"
```

The system should ensure that repeated processing of the same logical task does not accidentally produce repeated side effects where the underlying operation can support idempotency.

This distinction is important:

```text
Delivery semantics:
At-least-once

Application-level effect:
Can be made effectively once through idempotency
```

Do NOT claim universal exactly-once execution.

---

# 16. DATABASE

The database is the source of truth for task metadata and lifecycle state.

It should persist:

* Task
* Status
* Schedule time
* Attempts
* Errors
* Retry information
* Lease information
* Completion information

Important indexes will likely include:

```text
(status, scheduled_at)
```

and potentially:

```text
(status, lease_expires_at)
```

The database design should consider:

* Transactions
* Isolation
* Row locking
* Indexes
* Query performance
* Connection pooling
* Failure recovery

---

# 17. REDIS

If Redis is used, it should have a clear responsibility.

Possible uses:

* Distributed coordination
* Short-lived locks
* Caching
* Rate limiting
* Fast ephemeral state

But Redis should NOT unnecessarily become the source of truth if durable task metadata already lives in the database.

Every technology must have a reason.

Do not add technologies just to make the project look complicated.

---

# 18. FAILURE SCENARIOS WE MUST TEST

The project should deliberately test failures.

### Worker crash

```text
Worker receives task
Worker crashes
Task should eventually become retryable
```

### Scheduler crash

```text
Scheduler discovers task
Scheduler crashes
Task should not be permanently lost
```

### Duplicate scheduler

```text
Scheduler A + Scheduler B
Both see same task
Only one should successfully claim it
```

### Duplicate delivery

```text
Same task delivered twice
System should prevent unsafe duplicate side effects
```

### Queue outage

```text
Queue unavailable
System should fail safely
Task metadata should remain recoverable
```

### Database outage

```text
Database unavailable
API should return appropriate failure
No false success response
```

### Worker overload

```text
Huge number of tasks
Queue grows
Workers process according to capacity
```

### Long-running task

```text
Task runs for several minutes
Lease/heartbeat mechanism should prevent premature retry
```

### Process restart

```text
Kill service
Restart service
Pending tasks should recover
```

These failure tests are one of the most important parts of the project.

---

# 19. OBSERVABILITY

We should not just build the system.

We should prove that it works.

Use structured logging.

Example:

```json
{
  "event": "task_execution_failed",
  "task_id": "...",
  "worker_id": "...",
  "attempt": 3,
  "error": "...",
  "timestamp": "..."
}
```

Metrics should include:

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

Useful SLO-style metrics:

```text
p50 latency
p95 latency
p99 latency
success rate
retry rate
throughput
```

---

# 20. DISTRIBUTED TRACING

If practical, introduce tracing so that one task can be followed across:

```text
API
 ↓
Database
 ↓
Scheduler
 ↓
Queue
 ↓
Worker
 ↓
External operation
```

A correlation/task ID should make debugging easier.

For example:

```text
task_id = abc123
```

should appear in logs across services.

---

# 21. CONTAINERIZATION

Every major service should be containerized.

Potential services:

```text
api
scheduler
worker
database
redis
message broker
```

Use Docker Compose for local development.

Example:

```text
docker compose up
```

should start the complete local environment.

---

# 22. KUBERNETES

After the local system works correctly, we should demonstrate how it can scale.

For example:

```text
API:
3 replicas

Scheduler:
2 replicas

Workers:
10 replicas
```

Workers are naturally horizontally scalable:

```text
Worker 1
Worker 2
Worker 3
...
Worker N
```

Kubernetes concepts that may be demonstrated:

* Deployments
* Services
* ConfigMaps
* Secrets
* Health checks
* Readiness probes
* Liveness probes
* Horizontal scaling

But Kubernetes should come AFTER the core distributed system works locally.

---

# 23. SECURITY

The project should include basic production security practices.

Examples:

* Authentication
* Authorization
* Input validation
* Rate limiting
* Secrets through environment/configuration
* No hardcoded credentials
* Secure database credentials
* Proper error responses
* Payload limits

Never commit:

```text
passwords
API keys
tokens
secrets
.env
```

to Git.

---

# 24. TESTING STRATEGY

Testing should happen at multiple levels.

### Unit tests

Test:

* Retry calculation
* State transitions
* Validation
* Scheduling logic
* Idempotency logic

### Integration tests

Test:

```text
API + Database
Scheduler + Database
Scheduler + Queue
Worker + Database
Worker + Queue
```

### End-to-end tests

Example:

```text
Create task
   ↓
Persist
   ↓
Scheduler detects
   ↓
Queue
   ↓
Worker
   ↓
Execute
   ↓
SUCCESS
```

### Failure tests

Deliberately kill:

```text
worker
scheduler
queue
database connection
```

and verify recovery.

### Load testing

We should measure:

* Tasks/sec
* Queue throughput
* Worker throughput
* API latency
* p95/p99 latency
* Database performance
* Scaling behavior

Do not invent performance numbers.

All resume metrics must come from actual experiments.

---

# 25. PERFORMANCE BENCHMARKING

We want measurable results.

For example, after implementation we can test:

```text
100 tasks
1,000 tasks
10,000 tasks
100,000 tasks
```

Measure:

```text
submission throughput
scheduling latency
queue latency
execution latency
end-to-end latency
failure recovery time
```

Then document actual values.

For example, only if the experiment proves it:

```text
Processed X tasks/sec
p95 scheduling latency = Y ms
Recovery after worker crash = Z seconds
```

Never manufacture numbers for the resume.

---

# 26. PROJECT STRUCTURE

The final repository should be clean and professional.

Potential structure:

```text
distributed-task-scheduler/
│
├── services/
│   ├── api/
│   ├── scheduler/
│   └── worker/
│
├── shared/
│   ├── models/
│   ├── config/
│   ├── logging/
│   └── utilities/
│
├── database/
│   ├── migrations/
│   └── schemas/
│
├── tests/
│   ├── unit/
│   ├── integration/
│   ├── e2e/
│   └── failure/
│
├── docker/
│
├── k8s/
│
├── monitoring/
│
├── docs/
│
├── scripts/
│
├── docker-compose.yml
├── README.md
└── .gitignore
```

The exact structure can change based on the selected technology stack.

---

# 27. TECHNOLOGY SELECTION

Do not blindly assume technologies.

For every major technology we choose, explain:

1. What problem does it solve?
2. Why do we need it?
3. Why this technology?
4. What alternatives exist?
5. Why didn't we choose those alternatives?
6. What trade-offs does it introduce?

For example:

### PostgreSQL

Could be chosen because:

* Strong consistency
* Transactions
* Row-level locking
* Mature indexing
* Reliable persistence
* Excellent fit for task metadata

### Redis

Could be used for:

* Fast ephemeral coordination
* Distributed locks
* Caching
* Rate limiting

### Message broker

Could be used because:

* Decouples producers and consumers
* Provides buffering
* Supports asynchronous processing
* Enables worker scaling

But we need to decide the actual broker based on project requirements.

Possible choices may include:

* RabbitMQ
* Kafka
* Redis Streams
* SQS-like systems

Do not choose Kafka simply because it is popular.

The project should use the simplest architecture that convincingly demonstrates the intended concepts.

---

# 28. IMPORTANT ARCHITECTURAL TRADE-OFFS

I want the project to demonstrate that I understand trade-offs.

Examples:

### Polling vs event-driven scheduling

Polling is simpler but can introduce scheduling delay.

Event-driven systems can reduce latency but add complexity.

### Database locking vs Redis locking

Database locking can be sufficient when the database is already the source of truth.

Redis locking may reduce database coordination load but introduces another distributed dependency.

### At-least-once vs exactly-once

At-least-once is realistic.

Exactly-once execution with external side effects is extremely difficult.

### One scheduler vs multiple schedulers

One scheduler is simpler but creates a single point of failure.

Multiple schedulers improve availability but require coordination.

### More workers vs bigger workers

Horizontal scaling provides better fault isolation and throughput.

### Polling interval

Smaller intervals:

```text
lower scheduling latency
higher database load
```

Larger intervals:

```text
lower database load
higher scheduling latency
```

These trade-offs must be documented.

---

# 29. WHAT MAKES THIS A DISTRIBUTED SYSTEM?

The project should not just have multiple folders/processes.

The distributed-systems aspect comes from independent components communicating over networks and potentially failing independently.

For example:

```text
API
Scheduler
Queue
Worker 1
Worker 2
Worker 3
Database
```

Each component may:

* Crash
* Restart
* Become unavailable
* Experience network delay
* Process duplicate messages
* Race with another instance

Therefore we must reason about:

* Partial failure
* Concurrency
* Ordering
* Consistency
* Availability
* Durability
* Coordination

---

# 30. CAP THEOREM DISCUSSION

I should be able to explain CAP in relation to this project.

Do not simply say:

"CAP means choose two of three."

That is an oversimplification.

Instead explain:

Under a network partition, a distributed system must make a trade-off between consistency and availability.

For this project, task metadata correctness is more important than pretending the system can always accept operations during a database partition.

We should prioritize correctness and durability for task state.

---

# 31. FAILURE MODEL

We should explicitly define what failures we handle.

Expected failures:

```text
process crash
container crash
worker crash
scheduler crash
temporary queue failure
temporary database connectivity failure
duplicate delivery
network delay
worker timeout
```

Not necessarily guaranteed:

```text
permanent database destruction
catastrophic infrastructure loss
malicious attacks beyond defined security scope
```

The README should clearly state the system's guarantees and limitations.

---

# 32. RELIABILITY GUARANTEES

The final project should make carefully worded guarantees.

For example:

### Task durability

Accepted tasks are persisted before returning success.

### At-least-once processing

A task may be processed more than once in failure scenarios.

### Retry

Transient failures can trigger retries.

### Dead-letter handling

Tasks exceeding retry limits are isolated.

### Worker crash recovery

Tasks whose execution lease expires can be retried.

### Horizontal scalability

Multiple workers can consume tasks concurrently.

### Scheduler redundancy

Multiple scheduler instances can operate safely if task claiming is atomic.

Do NOT claim:

> Every task executes exactly once.

unless we have a very specific technically defensible mechanism and scope.

---

# 33. PROJECT DEVELOPMENT ORDER

This is extremely important.

Do NOT attempt to build everything simultaneously.

Build incrementally.

## Phase 1 — Understand the architecture

Before writing code, understand:

* Task lifecycle
* State machine
* Components
* Data model
* Scheduling
* Queueing
* Worker execution
* Failure scenarios

## Phase 2 — Basic API

Implement:

```text
POST /tasks
GET /tasks/{id}
```

Persist tasks.

## Phase 3 — Basic scheduler

Scheduler finds due tasks.

## Phase 4 — Queue integration

Scheduler publishes tasks.

## Phase 5 — Worker

Worker consumes and executes tasks.

## Phase 6 — State management

Implement reliable state transitions.

## Phase 7 — Retries

Add retry policy.

## Phase 8 — Dead-letter handling

Add retry exhaustion behavior.

## Phase 9 — Idempotency

Protect against duplicate processing.

## Phase 10 — Failure recovery

Worker crash recovery.

Scheduler crash recovery.

## Phase 11 — Multiple schedulers

Introduce concurrency-safe claiming.

## Phase 12 — Observability

Logs + metrics + tracing.

## Phase 13 — Testing

Unit + integration + E2E + failure tests.

## Phase 14 — Load testing

Measure actual system performance.

## Phase 15 — Docker

Containerize everything.

## Phase 16 — Kubernetes

Demonstrate horizontal scaling and health management.

## Phase 17 — Documentation

Create:

* Architecture diagram
* Sequence diagrams
* Failure scenarios
* Database schema
* API documentation
* Performance results
* Trade-offs
* Limitations

---

# 34. DEVELOPMENT RULE

We will work on the project **one task at a time**.

Do NOT dump the entire implementation at once.

For every implementation step:

1. Explain what we are building.
2. Explain why it exists.
3. Explain the design.
4. Explain alternatives.
5. Implement it.
6. Test it.
7. Verify it.
8. Explain what happened.
9. Only then move to the next step.

If something is unclear, stop and reason about it before writing code.

---

# 35. CODE QUALITY REQUIREMENTS

Code should be:

* Clean
* Modular
* Typed where appropriate
* Testable
* Maintainable
* Production-oriented
* Properly logged
* Properly error-handled

Avoid:

* Giant files
* Hardcoded configuration
* Magic numbers
* Silent exception handling
* Fake production behavior
* Unnecessary abstractions
* Copy-pasted code
* Overengineering

Do not introduce a framework/library unless it provides a meaningful benefit.

---

# 36. GIT WORKFLOW

Use Git professionally.

Commits should represent meaningful units of work.

Examples:

```text
feat: add task creation API
feat: implement scheduler polling
feat: add queue producer
feat: implement worker consumer
feat: add retry policy
test: add worker failure recovery tests
feat: add task metrics
docs: document scheduler architecture
```

Avoid commits like:

```text
update
changes
final
done
project
```

---

# 37. DOCUMENTATION

The final README should explain:

```text
1. Problem
2. Goals
3. Architecture
4. Components
5. Task lifecycle
6. Database schema
7. API
8. Scheduling algorithm
9. Queue architecture
10. Worker architecture
11. Retry mechanism
12. Idempotency
13. Failure recovery
14. Observability
15. Docker setup
16. Kubernetes deployment
17. Testing
18. Load testing
19. Performance results
20. Design trade-offs
21. Limitations
22. Future improvements
```

---

# 38. INTERVIEW PREPARATION

While building the project, I need to understand questions such as:

### Basic

* What problem does the system solve?
* Why is it distributed?
* Why use a queue?
* Why not execute tasks directly from the API?
* Why do we need workers?
* Why do we need a scheduler?

### Intermediate

* How does task scheduling work?
* How do you prevent duplicate scheduling?
* How do retries work?
* What happens if a worker crashes?
* What happens if the scheduler crashes?
* What happens if the queue is unavailable?
* How do you recover stuck tasks?
* Why do you need leases?
* What is idempotency?

### Advanced

* Why can't you guarantee exactly-once execution?
* How do multiple schedulers coordinate?
* What race conditions exist?
* How does row locking work?
* What isolation level is required?
* What happens during network partitions?
* What happens if a worker completes a task but crashes before acknowledging it?
* How do you prevent retry storms?
* How does backpressure work?
* How would you scale to millions of tasks?
* What becomes the bottleneck?
* How would you shard the scheduler?
* How would you partition the queue?
* How would you guarantee fairness?
* How would you implement priority queues?
* How would you handle clock skew?
* How would you handle recurring jobs?
* How would you perform zero-downtime deployments?

I should be able to answer these based on the actual implementation, not memorized theory.

---

# 39. IMPORTANT: DO NOT FABRICATE RESULTS

If we eventually report:

```text
10,000 tasks/sec
p95 = 80ms
99.99% reliability
40% latency reduction
```

those numbers must come from actual tests.

Never invent metrics.

If we don't have measurements, explicitly say:

```text
Not measured yet.
```

Then design an experiment to measure it.

---

# 40. FINAL PROJECT DEMONSTRATION

The final demo should show a real task flowing through the entire system.

Example:

```text
Client
  |
  | POST /tasks
  v
API
  |
  v
Database
  |
  | scheduled_at reached
  v
Scheduler
  |
  v
Queue
  |
  v
Worker
  |
  v
Task execution
  |
  v
Database
  |
  v
SUCCESS
```

Then demonstrate failure:

```text
Task
 ↓
Worker
 ↓
Worker crashes
 ↓
Lease expires
 ↓
Task becomes retryable
 ↓
Another worker executes
 ↓
SUCCESS
```

Then demonstrate duplicate protection/idempotency.

Then show metrics.

That makes the project much stronger than simply showing CRUD APIs.

---

# 41. WHAT I EXPECT FROM YOU

Act as a senior distributed-systems/backend engineer mentoring me.

Do not merely generate code.

For every important design decision, explain:

```text
WHAT
WHY
HOW
ALTERNATIVES
TRADE-OFFS
FAILURE MODES
TESTING
```

When teaching me, assume I am a CSE student who wants to deeply understand the project.

I want to be able to defend every major architectural decision during an interview or viva.

If I propose something technically weak, tell me directly.

If something is overengineered, tell me.

If a reliability claim is unrealistic, tell me.

If there is a simpler correct solution, prefer it.

Do not optimize for "looking impressive."

Optimize for:

```text
Correctness
+
Reliability
+
Understandability
+
Measurability
+
Real engineering quality
```

---

# 42. MOST IMPORTANT RULE

We are going to build this project **properly from the ground up**.

Before implementation:

1. Establish the final requirements.
2. Establish the architecture.
3. Establish the technology stack.
4. Establish the data model.
5. Establish the task state machine.
6. Establish reliability semantics.
7. Establish failure model.
8. Establish testing strategy.
9. Establish observability strategy.
10. Establish development milestones.

Then implement one milestone at a time.

Do not jump directly into coding.

At every stage, make sure I understand the system deeply enough to explain it myself.

---

# 43. FIRST THING TO DO

Do NOT start coding immediately.

First, review this project definition and produce a **complete engineering blueprint** containing:

1. Final problem statement
2. Functional requirements
3. Non-functional requirements
4. System architecture
5. Component responsibilities
6. Complete task lifecycle
7. State machine
8. Database schema
9. API design
10. Scheduler design
11. Queue design
12. Worker design
13. Retry architecture
14. Idempotency architecture
15. Failure recovery
16. Multi-scheduler coordination
17. Concurrency strategy
18. Consistency model
19. Reliability guarantees
20. Observability
21. Security
22. Testing strategy
23. Load-testing strategy
24. Docker architecture
25. Kubernetes architecture
26. Project folder structure
27. Development roadmap
28. Technology choices with alternatives and trade-offs
29. Expected interview questions
30. Known limitations
31. Future improvements

For each technology and architectural component, explain:

**what it is → why we need it → how it works in this project → alternatives → trade-offs → failure scenarios → how we test it.**

Do not write implementation code yet.

The goal of this first step is for both of us to have a precise, shared understanding of the system before implementation begins.

