// Small request builders shared by the Phase 2+ scripts.
import http from 'k6/http';
import { BASE_URL, placeOrder, randomCustomer, randomInt } from './quickdrop.js';
import { CATEGORIES } from './world.js';

export function orderInCity1(timeout = '60s') {
  return placeOrder(randomCustomer(1), randomInt(1, 2), [{ productId: randomInt(1, 500), quantity: 1 }], { timeout });
}

export function browse(timeout = '60s') {
  const category = CATEGORIES[randomInt(0, CATEGORIES.length - 1)];
  return http.get(`${BASE_URL}/api/stores/${randomInt(1, 6)}/products?category=${category}&limit=20`,
    { timeout, tags: { name: 'GET /api/stores/{id}/products' } });
}

export function history(timeout = '60s') {
  return http.get(`${BASE_URL}/api/customers/${randomInt(1, 30000)}/orders?limit=20`,
    { timeout, tags: { name: 'GET /api/customers/{id}/orders' } });
}

/** What a load balancer or Kubernetes liveness probe does every few seconds. */
export function health(timeout = '10s') {
  return http.get(`${BASE_URL}/actuator/health/liveness`, { timeout, tags: { name: 'health' } });
}
