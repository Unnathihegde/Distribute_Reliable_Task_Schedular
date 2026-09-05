# Distributed Reliable Task Scheduler

A production-style, fault-tolerant distributed task scheduling platform built with **Java 21**, **Spring Boot**, **PostgreSQL**, and **RabbitMQ**.

---

## 📊 Implementation Status

| Component / Module | Implementation Status | Test Status | Verified Features / Coverage |
|---|---|---|---|
| **`shared` Library** | ✅ Complete | ✅ 12 / 12 Passed | State machine, `CronNextRunCalculator`, `V1`-`V3` Flyway migrations, soft delete filtering |
| **`services/api`** | ✅ Complete | ✅ 26 / 26 Passed | REST controllers, payload & cron validation, `POST /retry`, `DELETE /{id}` soft delete |
| **`services/scheduler`** | ✅ Complete | ✅ 11 / 11 Passed | Poll loop, commit-then-publish, `FOR UPDATE SKIP LOCKED`, lease & stale QUEUED recovery sweeps |
| **`services/worker`** | ✅ Complete | ✅ 12 / 12 Passed | Lifecycle (a)-(g), `EmailTaskHandler`, `HttpTaskHandler`, recurring tasks parent/child scheduling |

---

## 🔍 Phase 8 Advanced Features Verification Matrix

| Feature | Code Implementation | Test Status | Verification Evidence / Test Method |
|---|---|---|---|
| **Flyway Migration V3** | ✅ [`V3__add_soft_delete.sql`](file:///c:/Users/unnat/Distribute_Reliable_Task_Schedular/shared/src/main/resources/db/migration/V3__add_soft_delete.sql) | ✅ **PASSED** | Next available migration applied automatically; schema at v3 |
| **Recurring Tasks** | ✅ [`TaskExecutionService.java`](file:///c:/Users/unnat/Distribute_Reliable_Task_Schedular/services/worker/src/main/java/com/scheduler/worker/execution/TaskExecutionService.java#L190-L220) | ✅ **PASSED** | `RecurringTaskIntegrationTest` — parent task `SUCCESS` creates child `SCHEDULED` task linked via `parent_task_id` |
| **Recurrence Isolation & Visibility** | ✅ Logged via `log.error(...)` | ✅ **PASSED** | Loud `ERROR` log output on cron parse/child creation failure; parent `SUCCESS` outcome preserved |
| **EmailTaskHandler** | ✅ [`EmailTaskHandler.java`](file:///c:/Users/unnat/Distribute_Reliable_Task_Schedular/services/worker/src/main/java/com/scheduler/worker/handler/EmailTaskHandler.java) | ✅ **PASSED** | `EmailTaskHandlerTest` — simulated email send with `to`/`subject` logging and idempotency key forwarding |
| **HttpTaskHandler** | ✅ [`HttpTaskHandler.java`](file:///c:/Users/unnat/Distribute_Reliable_Task_Schedular/services/worker/src/main/java/com/scheduler/worker/handler/HttpTaskHandler.java) | ✅ **PASSED** | `HttpTaskHandlerTest` — local JDK `HttpServer` webhook POST with 5s/10s timeouts, `X-Task-Id`, `X-Idempotency-Key`, 2xx success & 500 error retry trigger |
| **POST /tasks/{id}/retry** | ✅ [`TaskController.java`](file:///c:/Users/unnat/Distribute_Reliable_Task_Schedular/services/api/src/main/java/com/scheduler/api/controller/TaskController.java) | ✅ **PASSED** | `TaskApiAdvancedIntegrationTest` — resets `DEAD_LETTER` task to `SCHEDULED` (`attemptCount = 0`), 409 Conflict if not `DEAD_LETTER` |
| **DELETE /tasks/{id} (Soft Delete)** | ✅ [`TaskService.java`](file:///c:/Users/unnat/Distribute_Reliable_Task_Schedular/services/api/src/main/java/com/scheduler/api/service/TaskService.java) | ✅ **PASSED** | `TaskApiAdvancedIntegrationTest` — sets `deleted_at = now()`, excludes from GET/list/poll/sweep queries |

---

## 🧪 Running Tests

```bash
# Verify JDK 21
java -version

# Start infrastructure containers
docker compose up -d postgres rabbitmq redis

# Run full reactor test suite across all modules (61 tests total)
./mvnw clean test
```