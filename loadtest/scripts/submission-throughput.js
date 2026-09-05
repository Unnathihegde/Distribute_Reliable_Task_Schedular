import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const submissionLatency = new Trend('submission_latency_ms');
const submittedTasks = new Counter('submitted_tasks_total');

export const options = {
  stages: [
    { duration: '10s', target: 10 },
    { duration: '20s', target: 100 },
    { duration: '20s', target: 500 },
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<1000'],
  },
};

const API_BASE_URL = __ENV.TARGET_URL || 'http://localhost:8080';
const API_KEY = __ENV.API_KEY || 'dev-secret-api-key';

export default function () {
  const url = `${API_BASE_URL}/api/v1/tasks`;
  const payload = JSON.stringify({
    taskType: 'DEMO',
    payload: JSON.stringify({ message: 'submission_load_test', vu: __VU, iter: __ITER }),
    priority: 'MEDIUM',
    maxAttempts: 3,
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'X-API-Key': API_KEY,
    },
  };

  const res = http.post(url, payload, params);
  const success = check(res, {
    'status is 201 Created': (r) => r.status === 201,
    'has task ID': (r) => r.json('id') !== undefined,
  });

  if (success) {
    submissionLatency.add(res.timings.duration);
    submittedTasks.add(1);
  }

  sleep(0.01);
}

