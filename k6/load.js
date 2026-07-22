import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const degraded = new Counter('degraded_responses');
const businessErrors = new Counter('business_4xx');

export const options = {
  scenarios: {
    constant_load: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 300),   // req/s
      timeUnit: '1s',
      duration: __ENV.DURATION || '3m',
      preAllocatedVUs: 200,
      maxVUs: 2000,
    },
  },
  thresholds: {
    // Com o circuito ABERTO o fallback responde em <50ms:
    // p95 alto aqui indica que o fail-fast nao esta funcionando.
    http_req_duration: ['p(95)<3000'],
  },
};

export default function () {
  const id = Math.floor(Math.random() * 10000);
  const res = http.get(`${__ENV.TARGET}/api/pedidos/${id}`);

  check(res, {
    'status 200': (r) => r.status === 200,
  });

  if (res.headers['X-Degraded'] === 'true') {
    degraded.add(1);          // resposta veio do fallback
  }
  if (res.status >= 400 && res.status < 500) {
    businessErrors.add(1);    // BusinessException propagada (nao mascarada)
  }
}
