import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { server } from '@/shared/testing/mocks/server';
import { deliveryAccessDenied, deliveryError, deliverySessionExpired, getDelivery, getProof, listDeliveries, submitProof } from './deliveryApi';
import { getCourierProfile, provisionCourierProfile, updateCourierPersonalInfo } from './courierApi';

const parcel = '11111111-1111-4111-8111-111111111111';
const root = '/api/v1/courier/deliveries';
const evidence = { recipientName: 'Synthetic Recipient', notes: null, proofReference: 'SYNTHETIC-1' };

describe('courier delivery error messages', () => {
  it.each([
    ['COURIER_VERIFICATION_FAILED', 'We could not verify your courier access right now. Please try again later.'],
    ['PROOF_STORAGE_UNAVAILABLE', 'Delivery data is temporarily unavailable. Please try again later.'],
    ['UNKNOWN_CODE', 'Delivery services are temporarily unavailable. Please try again later.'],
    [undefined, 'Delivery services are temporarily unavailable. Please try again later.'],
  ])('handles HTTP 503 with code %s', async (code, message) => {
    server.use(http.get(`*${root}`, () => HttpResponse.json({
      type: 'about:blank', title: 'Service Unavailable', status: 503, code,
    }, { status: 503 })));
    const error = await listDeliveries('synthetic-token').catch((failure: unknown) => failure);
    expect(deliveryError(error)).toBe(message);
    expect(deliveryAccessDenied(error)).toBe(false);
    expect(deliverySessionExpired(error)).toBe(false);
  });

  it.each([
    [401, 'Your session has expired. Please sign in again.', false, true],
    [403, "You don't have access", true, false],
  ])('preserves HTTP %s authentication handling', async (status, message, accessDenied, sessionExpired) => {
    server.use(http.get(`*${root}`, () => HttpResponse.json({ code: 'COURIER_VERIFICATION_FAILED' }, { status })));
    const error = await listDeliveries('synthetic-token').catch((failure: unknown) => failure);
    expect(deliveryError(error)).toBe(message);
    expect(deliveryAccessDenied(error)).toBe(accessDenied);
    expect(deliverySessionExpired(error)).toBe(sessionExpired);
  });
});

describe('courier gateway API boundary', () => {
  it('sends real requests with the explicit bearer token only to configured gateway routes', async () => {
    const requests: { method: string; path: string; auth: string | null }[] = [];
    server.use(http.all('*', async ({ request }) => {
      const path = new URL(request.url).pathname;
      requests.push({ method: request.method, path, auth: request.headers.get('Authorization') });
      if (path === '/api/v1/users/courier-profile' || path === '/api/v1/courier/profile/provision')
        return HttpResponse.json({}, { status: 404 }); // Exercise both approved profile fallbacks.
      if (request.method === 'POST' && path.endsWith('/proof')) expect(await request.json()).toEqual(evidence);
      return HttpResponse.json(path === root ? [] : {});
    }));
    await expect(listDeliveries('synthetic-token')).resolves.toEqual([]);
    await getDelivery(parcel, 'synthetic-token');
    await getProof(parcel, 'synthetic-token');
    await submitProof(parcel, evidence, 'synthetic-token');
    await getCourierProfile('synthetic-token');
    await provisionCourierProfile('synthetic-token');
    await updateCourierPersonalInfo({ name: 'Synthetic Courier' }, 'synthetic-token');
    expect(requests).toHaveLength(9);
    expect(requests.every(request => request.auth === 'Bearer synthetic-token')).toBe(true);
    expect(requests.map(request => `${request.method} ${request.path}`)).toEqual([
      `GET ${root}`, `GET ${root}/${parcel}`, `GET ${root}/${parcel}/proof`, `POST ${root}/${parcel}/proof`,
      'GET /api/v1/users/courier-profile', 'GET /api/v1/courier/profile',
      'POST /api/v1/courier/profile/provision', 'POST /api/v1/users/courier-profile/provision', 'PATCH /api/v1/users/me',
    ]);
    const yaml = readFileSync(resolve(process.cwd(), '../../backend/api-gateway/src/main/resources/application.yml'), 'utf8');
    const predicates = [...yaml.matchAll(/- Path=([^\n]+)/g)].flatMap(match => match[1].trim().split(','));
    expect(predicates.length).toBeGreaterThan(0);
    for (const { path } of requests) {
      expect(predicates.some(pattern => pattern.endsWith('/**')
        ? path === pattern.slice(0, -3) || path.startsWith(pattern.slice(0, -2)) : path === pattern)).toBe(true);
    }
  });
  it('treats only PROOF_NOT_FOUND as absent evidence', async () => {
    server.use(http.get(`*${root}/${parcel}/proof`, () => HttpResponse.json({ code: 'ASSIGNMENT_NOT_FOUND' }, { status: 404 })));
    await expect(getProof(parcel, 'synthetic-token')).rejects.toMatchObject({ response: { status: 404 } });
    server.use(http.get(`*${root}/${parcel}/proof`, () => HttpResponse.json({ code: 'PROOF_NOT_FOUND' }, { status: 404 })));
    await expect(getProof(parcel, 'synthetic-token')).resolves.toBeNull();
  });
});
