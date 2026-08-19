// GoChat Load Test Suite — k6
// Run:  npx k6 run scripts/load_test.js
// Or:   k6 run scripts/load_test.js

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080/api/v1';

// Custom metrics
const errorRate = new Rate('errors');
const loginDuration = new Trend('login_duration');
const messageSendDuration = new Trend('message_send_duration');
const sessionListDuration = new Trend('session_list_duration');

export const options = {
  stages: [
    { duration: '15s', target: 10 },   // Ramp up to 10 users
    { duration: '30s', target: 50 },   // Ramp up to 50 users
    { duration: '1m',  target: 100 },  // Sustained at 100 users
    { duration: '30s', target: 50 },   // Scale down
    { duration: '15s', target: 0 },    // Drain
  ],
  thresholds: {
    http_req_duration: ['p(95)<500', 'p(99)<1500'],
    errors: ['rate<0.05'],
    login_duration: ['p(95)<800'],
    message_send_duration: ['p(95)<600'],
  },
};

const headers = { 'Content-Type': 'application/json' };

export default function () {
  const phone = `+1${Math.floor(1000000000 + Math.random() * 9000000000)}`;
  const name = `LoadUser_${__VU}_${__ITER}`;

  group('Registration & Login', () => {
    // Register
    const regRes = http.post(`${BASE_URL}/auth/register`, JSON.stringify({
      phone: phone,
      display_name: name,
      country_code: 'US',
    }), { headers });

    check(regRes, {
      'register status 200': (r) => r.status === 200,
    });
    errorRate.add(regRes.status !== 200);

    // Login
    const loginStart = Date.now();
    const loginRes = http.post(`${BASE_URL}/auth/login`, JSON.stringify({
      email: phone,
    }), { headers });

    loginDuration.add(Date.now() - loginStart);
    check(loginRes, {
      'login status 200': (r) => r.status === 200,
    });
    errorRate.add(loginRes.status !== 200);

    let token = '';
    try {
      const body = JSON.parse(loginRes.body);
      token = body.accessToken || '';
    } catch (_) {}

    if (!token) return;

    const authHeaders = {
      ...headers,
      'Authorization': `Bearer ${token}`,
    };

    // Get user profile
    group('Profile', () => {
      const profileRes = http.get(`${BASE_URL}/users/me`, { headers: authHeaders });
      check(profileRes, {
        'profile status 200 or 404': (r) => r.status === 200 || r.status === 404,
      });
    });

    // List active sessions
    group('Sessions', () => {
      const sessStart = Date.now();
      const sessRes = http.get(`${BASE_URL}/auth/sessions`, { headers: authHeaders });
      sessionListDuration.add(Date.now() - sessStart);
      check(sessRes, {
        'sessions status 200': (r) => r.status === 200,
      });
    });

    // Send message
    group('Messaging', () => {
      const msgStart = Date.now();
      const msgRes = http.post(`${BASE_URL}/chat/conversations`, JSON.stringify({
        type: 'direct',
        participant_ids: ['00000000-0000-0000-0000-000000000001'],
      }), { headers: authHeaders });

      messageSendDuration.add(Date.now() - msgStart);
      check(msgRes, {
        'conversation create 200 or 201': (r) => r.status === 200 || r.status === 201 || r.status === 409,
      });
    });

    // Audit logs
    group('Audit Logs', () => {
      const auditRes = http.get(`${BASE_URL}/auth/audit-logs`, { headers: authHeaders });
      check(auditRes, {
        'audit logs status 200': (r) => r.status === 200,
      });
    });
  });

  sleep(1);
}
