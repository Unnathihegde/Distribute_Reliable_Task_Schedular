import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const burstSubmitted = new Counter('burst_submitted_total');
const iterations = parseInt(__ENV.BURST_COUNT || '1000', 10);

export const options = {
  scenarios: {
    burst: {
      executor: 'shared-iterations',
      vus: 50,
      iterations: iterations,
      maxDuration: '60s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
  },
};

const API_BASE_URL = __ENV.TARGET_URL || 'http://localhost:8080';
const API_KEY = __ENV.API_KEY || 'dev-secret-api-key';

export default function () {
  const url = `${API_BASE_URL}/api/v1/tasks`;
  const payload = JSON.stringify({
    taskType: 'DEMO',
    payload: JSON.stringify({ burst: true, id: __ITER }),
    priority: 'HIGH',
    maxAttempts: 3,
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'X-API-Key': API_KEY,
    },
  };

  const res = http.post(url, payload, params);
  const ok = check(res, {
    'status is 201 Created': (r) => r.status === 201,
  });

  if (ok) {
    burstSubmitted.add(1);
  }
}

