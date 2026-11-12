import { describe, expect, it } from 'vitest';
import axios from 'axios';
import { http, HttpResponse } from 'msw';
import { server } from '@/shared/testing/mocks/server';
import {
  estimateFee,
  getPricingRule,
  getZoneByCode,
  getZones,
  toPricingError,
  updatePricingRule,
  type PricingRuleUpdateRequest,
} from './pricingApi';

const API = 'http://localhost:8080';

const RULE = {
  id: '00000000-0000-0000-0000-000000000001',
  ruleName: 'STANDARD',
  baseFee: 550.0,
  includedWeightKg: 1.0,
  additionalWeightRatePerKg: 120.0,
  distanceSurchargeOtherDistrict: 402.0,
  expressSurcharge: 300.0,
  perKmRate: 0.0,
  currency: 'LKR',
  active: true,
  updatedAt: '2026-11-05T10:00:00Z',
};

const UPDATE: PricingRuleUpdateRequest = {
  baseFee: 600,
  includedWeightKg: 1,
  additionalWeightRatePerKg: 125.5,
  distanceSurchargeOtherDistrict: 402,
  expressSurcharge: 300,
  perKmRate: 0,
  currency: 'LKR',
};

async function caught(promise: Promise<unknown>): Promise<unknown> {
  try {
    await promise;
  } catch (error) {
    return error;
  }
  throw new Error('expected the request to fail');
}

describe('pricingApi requests', () => {
  it('GET /api/v1/admin/pricing-rules maps the rule with numeric amounts', async () => {
    server.use(http.get(`${API}/api/v1/admin/pricing-rules`, () => HttpResponse.json(RULE)));

    const rule = await getPricingRule();

    expect(rule).toEqual(RULE);
    expect(typeof rule.baseFee).toBe('number');
  });

  it('accepts amounts sent as strings and defaults a missing currency to LKR', async () => {
    server.use(
      http.get(`${API}/api/v1/admin/pricing-rules`, () =>
        HttpResponse.json({ ...RULE, baseFee: '550.00', currency: null, updatedAt: undefined }),
      ),
    );

    const rule = await getPricingRule();

    expect(rule.baseFee).toBe(550);
    expect(rule.currency).toBe('LKR');
    expect(rule.updatedAt).toBeNull();
  });

  it('rejects a rule with a missing amount instead of showing a wrong value', async () => {
    server.use(
      http.get(`${API}/api/v1/admin/pricing-rules`, () => HttpResponse.json({ ...RULE, baseFee: null })),
    );

    await expect(getPricingRule()).rejects.toThrow('invalid baseFee');
  });

  it('PUT /api/v1/admin/pricing-rules sends exactly the update body and returns the saved rule', async () => {
    let body: unknown;
    server.use(
      http.put(`${API}/api/v1/admin/pricing-rules`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ ...RULE, ...UPDATE });
      }),
    );

    const saved = await updatePricingRule(UPDATE);

    expect(body).toEqual(UPDATE);
    expect(saved.baseFee).toBe(600);
    expect(saved.additionalWeightRatePerKg).toBe(125.5);
  });

  it('GET /api/v1/zones maps zones and treats a non-list body as empty', async () => {
    server.use(
      http.get(`${API}/api/v1/zones`, () =>
        HttpResponse.json([
          { id: 'z1', code: 'ZONE_COLOMBO_METRO', name: 'Colombo Metro', description: 'City', active: true },
          { id: 'z2', code: 'ZONE_CENTRAL', name: 'Central', description: '', active: true },
        ]),
      ),
    );

    const zones = await getZones();

    expect(zones).toHaveLength(2);
    expect(zones[0]).toEqual({
      id: 'z1', code: 'ZONE_COLOMBO_METRO', name: 'Colombo Metro', description: 'City', active: true,
    });
    expect(zones[1].description).toBeNull();

    server.use(http.get(`${API}/api/v1/zones`, () => HttpResponse.json({ unexpected: true })));
    await expect(getZones()).resolves.toEqual([]);
  });

  it('GET /api/v1/zones/{code} returns mapped zone by code and handles not found', async () => {
    server.use(
      http.get(`${API}/api/v1/zones/:code`, ({ params }) => {
        if (params.code === 'ZONE_COLOMBO_METRO') {
          return HttpResponse.json({
            id: 'z1',
            code: 'ZONE_COLOMBO_METRO',
            name: 'Colombo Metro',
            description: 'Western Province',
            active: true,
          });
        }
        return new HttpResponse(null, { status: 404 });
      }),
    );

    const zone = await getZoneByCode('ZONE_COLOMBO_METRO');
    expect(zone).toEqual({
      id: 'z1',
      code: 'ZONE_COLOMBO_METRO',
      name: 'Colombo Metro',
      description: 'Western Province',
      active: true,
    });

    const error = (await caught(getZoneByCode('UNKNOWN'))) as { response?: { status: number } };
    expect(error.response?.status).toBe(404);
  });

  it('POST /api/v1/parcels/estimate sends the request and keeps only breakdown lines that are present', async () => {
    let body: unknown;
    server.use(
      http.post(`${API}/api/v1/parcels/estimate`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({
          fee: 1072.0,
          formattedFee: 'LKR 1,072',
          currency: 'LKR',
          estimatedDays: 3,
          breakdown: { baseFee: 550.0, weightFee: 120.0, distanceSurcharge: 402.0 },
        });
      }),
    );
    const request = {
      destinationType: 'OTHER_DISTRICT' as const,
      parcelSize: 'MEDIUM' as const,
      weightKg: 2,
      isExpress: false,
      currency: 'LKR',
    };

    const estimate = await estimateFee(request);

    expect(body).toEqual(request);
    expect(estimate.fee).toBe(1072);
    expect(estimate.estimatedDays).toBe(3);
    expect(estimate.breakdown).toEqual({ baseFee: 550, weightFee: 120, distanceSurcharge: 402 });
    expect('expressFee' in estimate.breakdown).toBe(false);
  });
});

describe('toPricingError', () => {
  it('400 with invalidFields becomes a validation error with messages per field', async () => {
    server.use(
      http.put(`${API}/api/v1/admin/pricing-rules`, () =>
        HttpResponse.json(
          {
            title: 'Bad Request',
            status: 400,
            detail: 'Validation failed for request.',
            fieldErrors: { baseFee: 'must be greater than or equal to 0.00' },
            invalidFields: { baseFee: 'must be greater than or equal to 0.00', perKmRate: 'must not be null' },
          },
          { status: 400 },
        ),
      ),
    );

    const error = toPricingError(await caught(updatePricingRule(UPDATE)), 'Could not save.');

    expect(error).toEqual({
      kind: 'validation',
      status: 400,
      message: 'Validation failed for request.',
      fieldErrors: { baseFee: 'must be greater than or equal to 0.00', perKmRate: 'must not be null' },
    });
  });

  it('400 with only a detail is still a validation error with that message', async () => {
    server.use(
      http.post(`${API}/api/v1/parcels/estimate`, () =>
        HttpResponse.json({ status: 400, detail: 'Unsupported currency: USD' }, { status: 400 }),
      ),
    );

    const error = toPricingError(await caught(estimateFee({
      destinationType: 'SAME_CITY', parcelSize: 'SMALL', weightKg: 1, isExpress: false, currency: 'USD',
    })), 'Could not calculate.');

    expect(error.kind).toBe('validation');
    expect(error.message).toBe('Unsupported currency: USD');
    expect(error.fieldErrors).toEqual({});
  });

  it('400 without a detail uses the first field message', () => {
    const error = toPricingError(
      new axios.AxiosError('bad', 'ERR_BAD_REQUEST', undefined, undefined, {
        status: 400, statusText: 'Bad Request', headers: {}, config: {} as never,
        data: { invalidFields: { weightKg: 'Weight cannot exceed 100 kg', ignored: 7 } },
      }),
      'fallback',
    );

    expect(error.message).toBe('Weight cannot exceed 100 kg');
    expect(error.fieldErrors).toEqual({ weightKg: 'Weight cannot exceed 100 kg' });
  });

  it.each([
    [401, 'unauthenticated', 'Your session has expired. Please sign in again.'],
    [403, 'forbidden', 'Your account does not have permission to do that.'],
  ])('%i becomes %s with a clear message', async (status, kind, message) => {
    server.use(http.get(`${API}/api/v1/admin/pricing-rules`, () => new HttpResponse(null, { status })));

    const error = toPricingError(await caught(getPricingRule()), 'Could not load.');

    expect(error).toMatchObject({ kind, message, status, fieldErrors: {} });
  });

  it('503 becomes unavailable and keeps the server detail', async () => {
    server.use(
      http.get(`${API}/api/v1/admin/pricing-rules`, () =>
        HttpResponse.json({ status: 503, detail: 'Pricing data is temporarily unavailable.' }, { status: 503 }),
      ),
    );

    const error = toPricingError(await caught(getPricingRule()), 'Could not load.');

    expect(error).toMatchObject({ kind: 'unavailable', status: 503, message: 'Pricing data is temporarily unavailable.' });
  });

  it('500 without a body becomes unavailable with a generic message', async () => {
    server.use(http.get(`${API}/api/v1/zones`, () => new HttpResponse(null, { status: 500 })));

    const error = toPricingError(await caught(getZones()), 'Could not load.');

    expect(error).toMatchObject({ kind: 'unavailable', message: 'Pricing is temporarily unavailable. Please try again.' });
  });

  it('a network failure becomes a network error', async () => {
    server.use(http.get(`${API}/api/v1/zones`, () => HttpResponse.error()));

    const error = toPricingError(await caught(getZones()), 'Could not load.');

    expect(error).toEqual({
      kind: 'network',
      message: 'We could not reach Zentro. Check your connection and try again.',
      fieldErrors: {},
    });
  });

  it('other statuses and non-HTTP errors use the fallback message', () => {
    expect(toPricingError(new Error('boom'), 'Fallback')).toEqual({ kind: 'unknown', message: 'Fallback', fieldErrors: {} });
    const conflict = new axios.AxiosError('conflict', 'ERR_BAD_REQUEST', undefined, undefined, {
      status: 409, statusText: 'Conflict', headers: {}, config: {} as never, data: {},
    });
    expect(toPricingError(conflict, 'Fallback')).toEqual({ kind: 'unknown', message: 'Fallback', fieldErrors: {}, status: 409 });
  });
});
