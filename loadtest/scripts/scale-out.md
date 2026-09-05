# Scale-Out Manual Testing Procedure

## Objective
Measure throughput scalability across 1, 2, 4, and 8 worker service replicas to observe worker dispatch and execution scaling per Section 23 of `docs/BLUEPRINT.md`.

## Prerequisites
- Local Docker Compose environment running with Postgres, RabbitMQ, Redis, API, and Scheduler.
- `k6` load testing tool installed locally or executed via binary (`loadtest/bin/k6-v0.52.0-windows-amd64/k6.exe` or `k6`).
- **Operational Note on Docker Scaling**: Standard `docker-compose.yml` specifies a fixed `container_name: scheduler-worker` for the worker service. To scale using `docker compose up -d --scale worker=N`, ensure `container_name` is commented out or overridden in `docker-compose.override.yml`, as Docker Compose requires non-conflicting container names for multi-replica scaling.

## Procedure

For each replica count $N \in \{1, 2, 4, 8\}$:

1. **Prepare Worker Container Scaling**:
   Remove or comment out `container_name: scheduler-worker` in `docker-compose.yml` (or provide `docker-compose.override.yml` without fixed `container_name`).

2. **Scale Worker Replicas**:
   ```bash
   docker compose up -d --scale worker=N
   ```

3. **Verify Replica Count & Worker Health**:
   ```bash
   docker compose ps worker
   ```

4. **Execute End-to-End Latency & Throughput Benchmark**:
   ```bash
   k6 run loadtest/scripts/end-to-end-latency.js
   ```

5. **Execute Burst Submission Benchmark**:
   ```bash
   k6 run -e BURST_COUNT=1000 loadtest/scripts/burst-handling.js
   ```

6. **Record Metrics**:
   - Worker execution completion rate (tasks/sec)
   - End-to-end processing latency percentiles (p50 / p95 / p99)
   - Database / Queue drain rate (`SELECT status, count(*) FROM tasks GROUP BY status;`)
   - CPU and memory resource usage via `docker stats`

7. **Reset Replica Count**:
   ```bash
   docker compose up -d --scale worker=1
   ```

