import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const e2eLatency = new Trend('e2e_processing_latency_ms');
const completedTasks = new Counter('completed_tasks_total');

export const options = {
  vus: 10,
  duration: '30s',
  thresholds: {
    http_req_failed: ['rate<0.05'],
  },
};

const API_BASE_URL = __ENV.TARGET_URL || 'http://localhost:8080';
const API_KEY = __ENV.API_KEY || 'dev-secret-api-key';

export default function () {
  const createUrl = `${API_BASE_URL}/api/v1/tasks`;
  const payload = JSON.stringify({
    taskType: 'DEMO',
    payload: JSON.stringify({ timestamp: Date.now() }),
    priority: 'HIGH',
    maxAttempts: 3,
  });

  const headers = {
    'Content-Type': 'application/json',
    'X-API-Key': API_KEY,
  };

  const createRes = http.post(createUrl, payload, { headers });
  if (createRes.status !== 201) {
    return;
  }

  const taskId = createRes.json('id');
  const startTime = Date.now();
  let status = 'SCHEDULED';
  let attempts = 0;
  const maxPolls = 60; // Max 30 seconds polling

  while (status !== 'SUCCESS' && status !== 'DEAD_LETTER' && status !== 'CANCELLED' && attempts < maxPolls) {
    sleep(0.5);
    attempts++;

    const getRes = http.get(`${API_BASE_URL}/api/v1/tasks/${taskId}`, { headers });
    if (getRes.status === 200) {
      status = getRes.json('status');
      if (status === 'SUCCESS') {
        const e2eMs = Date.now() - startTime;
        e2eLatency.add(e2eMs);
        completedTasks.add(1);
        break;
      }
    }
  }

  check(status, {
    'task reached SUCCESS state': (s) => s === 'SUCCESS',
  });
}

