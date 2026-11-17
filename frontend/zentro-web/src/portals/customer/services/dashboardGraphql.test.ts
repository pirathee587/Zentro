import { describe, it, expect, afterEach } from 'vitest';
import { http, HttpResponse } from 'msw';
import { server } from '@/shared/testing/mocks/server';
import { setAccessToken } from '@/shared/api/apiClient';
import { getDashboardData, getDashboardTracking, getDashboardLiveParcel, estimateDashboardFee } from './dashboardGraphql';

const summary = { activeCount: 0, deliveredCount: 0, awaitingPickupCount: 0, failedCount: 0,
  inTransitCount: 0, totalParcels: 0, totalFees: [], monthlyFees: [] };
afterEach(() => setAccessToken(undefined));

describe('customer dashboard GraphQL', () => {
  it('uses the existing JWT client for all dashboard requests and handles an empty account', async () => {
    setAccessToken('synthetic-customer-jwt');
    const operations: string[] = [];
    server.use(http.post('*/graphql', async ({ request }) => {
      expect(request.headers.get('Authorization')).toBe('Bearer synthetic-customer-jwt');
      const body = await request.json() as { query: string; variables: Record<string, unknown> };
      operations.push(body.query);
      expect(body.variables).not.toHaveProperty('customerId');
      if (body.query.includes('customerDashboardSummary')) return HttpResponse.json({ data: { customerDashboardSummary: summary } });
      if (body.query.includes('myDeliveries')) {
        expect(body.variables).toEqual({ page: 1, size: 4 });
        return HttpResponse.json({ data: { myDeliveries: { items: [] } } });
      }
      return HttpResponse.json({ data: { customerLiveDelivery: null } });
    }));
    const data = await getDashboardData();
    expect(operations).toHaveLength(3);
    expect(data.totalParcels).toBe(0);
    expect(data.recentParcels).toEqual([]);
    expect(data.liveParcel).toBeNull();
    expect(data.courier).toBeUndefined();
    expect(data.notifications).toEqual([]);
    expect(data.formattedMonthlySpent).toBe('No spending recorded');
  });

  it('rejects GraphQL failures and never substitutes mock values', async () => {
    server.use(http.post('*/graphql', () => HttpResponse.json({ data: null,
      errors: [{ message: 'Denied', extensions: { classification: 'UNAUTHORIZED' } }] })));
    await expect(getDashboardData()).rejects.toThrow('Please sign in');
    await expect(getDashboardLiveParcel()).rejects.toThrow('Please sign in');
  });

  it('public search requests only permitted fields and maps actual cities/status', async () => {
    server.use(http.post('*/graphql', async ({ request }) => {
      const body = await request.json() as { query: string; variables: Record<string, unknown> };
      expect(body.variables).toEqual({ trackingRef: 'ZT-OWNED123' });
      expect(body.query).not.toMatch(/phone|address|recipient\s*\{|sender\s*\{|fee/);
      return HttpResponse.json({ data: { publicTracking: { trackingRef: 'ZT-OWNED123', status: 'OUT_FOR_DELIVERY', originCity: 'Colombo', destinationCity: 'Kandy' } } });
    }));
    expect(await getDashboardTracking('ZT-OWNED123')).toEqual({ trackingRef: 'ZT-OWNED123', status: 'In progress', sender: { city: 'Colombo' }, recipient: { city: 'Kandy' } });
    await expect(getDashboardTracking('../')).rejects.toThrow('valid tracking reference');
  });

  it('returns a genuine not-found result and does not suppress network errors', async () => {
    server.use(http.post('*/graphql', () => HttpResponse.json({ errors: [{ extensions: { classification: 'NOT_FOUND' } }] })));
    expect(await getDashboardTracking('ZT-MISSING')).toBeNull();
    server.use(http.post('*/graphql', () => HttpResponse.error()));
    await expect(getDashboardTracking('ZT-MISSING')).rejects.toThrow();
  });

  it('quotes through GraphQL rather than the customer mock adapter', async () => {
    server.use(http.post('*/graphql', async ({ request }) => {
      const body = await request.json() as { query: string; variables: Record<string, unknown> };
      expect(body.query).toContain('dashboardFeeEstimate');
      expect(body.variables.weightKg).toBe(2);
      return HttpResponse.json({ data: { dashboardFeeEstimate: { fee: 925.5, formattedFee: 'LKR 925.50', currency: 'LKR', estimatedDays: 3, breakdown: { baseFee: 500, weightFee: 425.5 } } } });
    }));
    const response = await estimateDashboardFee({ pickupCity: 'Colombo', deliveryCity: 'Kandy', weightKg: 2, destinationType: 'OTHER_DISTRICT' });
    expect(response.fee).toBe(925.5);
  });
});
