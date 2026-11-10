import { afterEach, describe, expect, expectTypeOf, it, vi } from 'vitest';
import { AxiosError, CanceledError, type InternalAxiosRequestConfig } from 'axios';
import { apiClient, setAccessToken } from '@/shared/api/apiClient';
import { getMerchantOrder, type MerchantOrder } from '../merchantApi';

const originalAdapter = apiClient.defaults.adapter;
const orderId = '3f1c2b7e-8a4d-4c1e-9b2a-5d6e7f8a9b0c';
const auth = { accessToken: 'merchant-token' };
const order: MerchantOrder = {
  id: orderId,
  trackingRef: 'ZT-7K3QX9M2PA',
  externalOrderRef: 'ORD-2026-11-9876',
  merchantId: 'merchant-subject',
  status: 'IN_TRANSIT',
  fee: 670,
  currency: 'LKR',
  estimatedDays: 2,
  createdAt: '2026-11-03T10:15:30Z',
  sender: { name: 'Store', phone: '0112345678', address: '77 Galle Road', city: null },
  recipient: { name: 'Recipient', phone: '0771234567', address: '15 Peradeniya Road', city: 'Kandy' },
  parcel: { weightKg: 1.5, category: 'Electronics', size: null, description: null },
};

afterEach(() => {
  apiClient.defaults.adapter = originalAdapter;
  setAccessToken(undefined);
});

describe('merchant order detail API', () => {
  it('requests the order UUID with the refreshed bearer token and returns the complete typed DTO', async () => {
    const refreshAccessToken = vi.fn(async () => 'fresh-token');
    setAccessToken('stale-shared-token');
    const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => {
      expect(config.url).toBe(`/api/v1/merchant/orders/${orderId}`);
      expect(config.method).toBe('get');
      expect(config.headers.Authorization).toBe('Bearer fresh-token');
      return { data: order, status: 200, statusText: 'OK', headers: {}, config };
    });
    apiClient.defaults.adapter = adapter;

    const result = await getMerchantOrder({ ...auth, refreshAccessToken }, orderId);
    expectTypeOf(result).toEqualTypeOf<MerchantOrder>();
    expectTypeOf(result.sender.city).toEqualTypeOf<string | null>();
    expectTypeOf(result.parcel.size).toEqualTypeOf<'SMALL' | 'MEDIUM' | 'LARGE' | null>();
    expectTypeOf(result.parcel.description).toEqualTypeOf<string | null>();
    expect(result).toEqual(order);
    expect(refreshAccessToken).toHaveBeenCalledTimes(1);
    expect(refreshAccessToken).toHaveBeenCalledWith(false);
    expect(adapter).toHaveBeenCalledTimes(1);
  });

  it('uses the supplied bearer token when no refresh callback is provided', async () => {
    apiClient.defaults.adapter = async (config) => {
      expect(config.headers.Authorization).toBe('Bearer merchant-token');
      return { data: order, status: 200, statusText: 'OK', headers: {}, config };
    };
    await expect(getMerchantOrder(auth, orderId)).resolves.toEqual(order);
  });

  it.each([{}, { accessToken: '' }, { refreshAccessToken: async () => undefined }])(
    'rejects a missing token before making a request (%j)', async (missingAuth) => {
      const adapter = vi.fn();
      apiClient.defaults.adapter = adapter;
      setAccessToken('shared-token-must-not-be-used');
      await expect(getMerchantOrder(missingAuth, orderId)).rejects.toThrow('Your sign-in session has ended.');
      expect(adapter).not.toHaveBeenCalled();
    },
  );

  it.each(['', ' ', 'not-a-uuid', order.trackingRef, order.externalOrderRef, `${orderId}/events`, ` ${orderId}`, `${orderId}?x=1`])(
    'rejects invalid order ID %j before authentication or transport', async (id) => {
      const adapter = vi.fn();
      const refreshAccessToken = vi.fn(async () => 'token');
      apiClient.defaults.adapter = adapter;
      await expect(getMerchantOrder({ refreshAccessToken }, id)).rejects.toThrow('A valid order UUID is required.');
      expect(refreshAccessToken).not.toHaveBeenCalled();
      expect(adapter).not.toHaveBeenCalled();
    },
  );

  it.each([401, 403, 404])('preserves normalized HTTP %i errors without retrying', async (status) => {
    const refreshAccessToken = vi.fn(async () => 'token');
    const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => {
      const response = { data: { title: 'Request rejected', detail: 'Order not found.' }, status, statusText: 'Error', headers: {}, config };
      throw new AxiosError('Request failed', 'ERR_BAD_REQUEST', config, undefined, response);
    });
    apiClient.defaults.adapter = adapter;

    // S2 intentionally uses the same 404 for missing and foreign-owned orders.
    await expect(getMerchantOrder({ refreshAccessToken }, orderId)).rejects.toMatchObject({
      category: 'http', status, problem: { detail: 'Order not found.' },
    });
    expect(adapter).toHaveBeenCalledTimes(1);
    expect(refreshAccessToken).toHaveBeenCalledTimes(1);
    expect(refreshAccessToken).toHaveBeenCalledWith(false);
  });

  it('passes the AbortSignal through and preserves in-flight cancellation', async () => {
    const controller = new AbortController();
    let started!: () => void;
    const ready = new Promise<void>((resolve) => { started = resolve; });
    apiClient.defaults.adapter = (config) => new Promise((_, reject) => {
      expect(config.signal).toBe(controller.signal);
      config.signal?.addEventListener?.('abort', () => reject(new CanceledError('canceled', config)));
      started();
    });

    const pending = getMerchantOrder({ ...auth, signal: controller.signal }, orderId);
    const rejection = expect(pending).rejects.toMatchObject({ category: 'canceled', code: 'ERR_CANCELED' });
    await ready;
    controller.abort();
    await rejection;
  });

  it('preserves normalized network failures without an API-layer retry', async () => {
    const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => { throw new AxiosError('Network Error', 'ERR_NETWORK', config); });
    apiClient.defaults.adapter = adapter;
    await expect(getMerchantOrder(auth, orderId)).rejects.toMatchObject({ category: 'network', code: 'ERR_NETWORK' });
    expect(adapter).toHaveBeenCalledTimes(1);
  });
});
