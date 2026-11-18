import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { server } from '@/shared/testing/mocks/server';
import { apiClient } from '@/shared/api/apiClient';
import { CourierDashboardError, getCourierTodayDashboard } from './courierDashboardApi';
import { dashboardDelivery, dashboardEnvelope, dashboardFixture } from '../pages/__tests__/dashboardFixtures';

describe('Courier Today GraphQL adapter', () => {
  it('sends a named query, pagination, explicit bearer and cancellation through the shared client', async () => {
    const spy = vi.spyOn(apiClient, 'post');
    const controller = new AbortController();
    server.use(http.post('*/graphql', async ({ request }) => {
      expect(request.headers.get('Authorization')).toBe('Bearer synthetic-courier');
      const body = await request.json() as { operationName: string; query: string; variables: unknown };
      expect(body.operationName).toBe('CourierTodayDashboard');
      expect(body.query).toContain('query CourierTodayDashboard');
      expect(body.query).toContain('parcelId'); expect(body.query).not.toMatch(/\bmutation\b/);
      expect(body.variables).toEqual({ page: 2, size: 10 });
      return HttpResponse.json(dashboardEnvelope());
    }));
    try {
      const result = await getCourierTodayDashboard(2, 10, 'synthetic-courier', controller.signal);
      expect(result.partial).toBe(false); expect(result.dashboard.deliveries).toHaveLength(1);
      expect(spy.mock.calls[0][2]).toMatchObject({ signal: controller.signal, timeout: 20_000 });
    } finally { spy.mockRestore(); }
  });
  it.each([[401, 'unauthorized'], [403, 'forbidden'], [503, 'dependency']] as const)('maps HTTP %s without exposing backend details', async (status, kind) => {
    server.use(http.post('*/graphql', () => HttpResponse.json({ detail: 'PRIVATE-BACKEND-DETAIL' }, { status })));
    await expect(getCourierTodayDashboard(1, 10, 'synthetic')).rejects.toMatchObject({ kind });
  });
  it.each([
    [{ classification: 'UNAUTHORIZED' }, 'unauthorized'], [{ classification: 'FORBIDDEN' }, 'forbidden'],
    [{ code: 'COURIER_PROFILE_NOT_FOUND' }, 'profile'], [{ code: 'ASSIGNMENTS_CHANGED' }, 'ownership'],
  ])('rejects unsafe HTTP 200 partial success: %j', async (extensions, kind) => {
    server.use(http.post('*/graphql', () => HttpResponse.json({ ...dashboardEnvelope(), errors: [{
      message: 'PRIVATE-BACKEND-DETAIL', path: ['courierTodayDashboard'], extensions,
    }] })));
    const failure = await getCourierTodayDashboard(1, 10, 'synthetic').catch(error => error);
    expect(failure).toBeInstanceOf(CourierDashboardError); expect(failure.kind).toBe(kind);
    expect(failure.message).not.toContain('PRIVATE');
  });
  it('returns available partial data with a flag and discards raw GraphQL messages', async () => {
    server.use(http.post('*/graphql', () => HttpResponse.json({ ...dashboardEnvelope(), errors: [{
      message: 'PRIVATE-ERROR', path: ['courierTodayDashboard', 'deliveries', 0, 'recipient'], extensions: { classification: 'INTERNAL_ERROR' },
    }] })));
    const result = await getCourierTodayDashboard(1, 10, 'synthetic');
    expect(result.partial).toBe(true); expect(JSON.stringify(result)).not.toContain('PRIVATE');
  });
  it('does not expose tracking details when the projection is unavailable', async () => {
    const row = dashboardDelivery({ projectionAvailable: false, trackingRef: 'STALE-REF',
      recipient: { name: 'STALE-NAME', phone: 'STALE-PHONE', address: 'STALE-ADDRESS', city: null } });
    server.use(http.post('*/graphql', () => HttpResponse.json(dashboardEnvelope([row]))));
    const result = await getCourierTodayDashboard(1, 10, 'synthetic');
    expect(result.dashboard.deliveries[0].recipient).toBeNull();
    expect(JSON.stringify(result)).not.toContain('STALE');
  });
  it('fails closed on missing data or an invalid contract', async () => {
    server.use(http.post('*/graphql', () => HttpResponse.json({ data: { courierTodayDashboard: null } })));
    await expect(getCourierTodayDashboard(1, 10, 'synthetic')).rejects.toMatchObject({ kind: 'invalid' });
    server.use(http.post('*/graphql', () => HttpResponse.json({ data: { courierTodayDashboard: {
      ...dashboardFixture(), counts: { assigned: 1, delivered: 2, failed: 0, remaining: 1 },
    } } })));
    await expect(getCourierTodayDashboard(1, 10, 'synthetic')).rejects.toMatchObject({ kind: 'invalid' });
  });
  it('maps root dependency failures to a safe retryable error', async () => {
    server.use(http.post('*/graphql', () => HttpResponse.json({ data: { courierTodayDashboard: null },
      errors: [{ message: 'PRIVATE-DB', path: ['courierTodayDashboard'], extensions: { code: 'ASSIGNMENT_SNAPSHOT_UNAVAILABLE' } }] })));
    const failure = await getCourierTodayDashboard(1, 10, 'synthetic').catch(error => error);
    expect(failure.retryable).toBe(true); expect(failure.message).toContain('temporarily unavailable');
    expect(failure.message).not.toContain('PRIVATE');
  });
  it('maps network failures and preserves cancellation', async () => {
    server.use(http.post('*/graphql', () => HttpResponse.error()));
    await expect(getCourierTodayDashboard(1, 10, 'synthetic')).rejects.toMatchObject({ kind: 'network' });
    const controller = new AbortController(); controller.abort();
    const error = await getCourierTodayDashboard(1, 10, 'synthetic', controller.signal).catch(failure => failure);
    expect(error).toMatchObject({ code: 'ERR_CANCELED' });
  });
});
