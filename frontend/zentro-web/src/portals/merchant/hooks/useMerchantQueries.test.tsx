import type { ReactNode } from 'react';
import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AxiosError, CanceledError, type InternalAxiosRequestConfig } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient, setAccessToken } from '@/shared/api/apiClient';
import { queryClient } from '@/shared/api/queryClient';
import type { AuthUser } from '@/shared/auth/authContext';
import { isMerchantOrderId, type MerchantOrder } from '../api/merchantApi';
import { merchantQueryKeys, useMerchantOrderQuery } from './useMerchantQueries';

const auth = vi.hoisted(() => ({
  user: null as AuthUser | null,
  loading: false,
  refreshAccessToken: vi.fn<() => Promise<string | undefined>>(),
}));
vi.mock('@/shared/auth/useAuth', () => ({ useAuth: () => auth }));

const orderId = '3f1c2b7e-8a4d-4c1e-9b2a-5d6e7f8a9b0c';
const otherId = '4f1c2b7e-8a4d-4c1e-9b2a-5d6e7f8a9b0c';
const originalAdapter = apiClient.defaults.adapter;
let client: QueryClient;
const order: MerchantOrder = {
  id: orderId, trackingRef: 'ZT-12345678', externalOrderRef: 'EXT-1', merchantId: 'merchant-a',
  status: 'CREATED', fee: 100, currency: 'LKR', estimatedDays: 2, createdAt: '2026-11-03T10:15:30Z',
  sender: { name: 'Store', phone: '0112345678', address: 'Store address', city: null },
  recipient: { name: 'Recipient', phone: '0771234567', address: 'Recipient address', city: null },
  parcel: { weightKg: 1, category: 'Books', size: null, description: null },
};

function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

function success(config: InternalAxiosRequestConfig, data = order) {
  return { data, status: 200, statusText: 'OK', headers: {}, config };
}

beforeEach(() => {
  client = new QueryClient({ defaultOptions: queryClient.getDefaultOptions() });
  auth.user = { id: 'merchant-a', accessToken: 'token-a', roles: ['MERCHANT'] };
  auth.loading = false;
  auth.refreshAccessToken.mockReset();
  auth.refreshAccessToken.mockImplementation(async () => auth.user?.accessToken);
});

afterEach(() => {
  cleanup();
  client.clear();
  apiClient.defaults.adapter = originalAdapter;
  setAccessToken(undefined);
});

describe('merchant order detail query', () => {
  it('loads authenticated details using the existing token acquisition flow', async () => {
    const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => {
      expect(config.url).toBe(`/api/v1/merchant/orders/${orderId}`);
      expect(config.headers.Authorization).toBe('Bearer token-a');
      expect(config.signal).toBeDefined();
      return success(config);
    });
    apiClient.defaults.adapter = adapter;
    const { result } = renderHook(() => useMerchantOrderQuery(orderId), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual(order);
    expect(auth.refreshAccessToken).toHaveBeenCalledWith(false);
    expect(adapter).toHaveBeenCalledTimes(1);
  });

  it('separates principals and order IDs without identity or tokens in keys', () => {
    const key = merchantQueryKeys.order(orderId, 'merchant-a');
    expect(key).toEqual(merchantQueryKeys.order(orderId, 'merchant-a'));
    expect(key).not.toEqual(merchantQueryKeys.order(orderId, 'merchant-b'));
    expect(key).not.toEqual(merchantQueryKeys.order(otherId, 'merchant-a'));
    expect(key).not.toEqual(merchantQueryKeys.orders('merchant-a'));
    expect(JSON.stringify(key)).toContain(orderId);
    expect(JSON.stringify(key)).not.toMatch(/merchant-a|token-a/);
  });

  it('fetches distinct details when the order ID changes', async () => {
    apiClient.defaults.adapter = async (config) => success(config, { ...order, id: config.url!.split('/').pop()! });
    const { result, rerender } = renderHook(({ id }) => useMerchantOrderQuery(id), { wrapper, initialProps: { id: orderId } });
    await waitFor(() => expect(result.current.data?.id).toBe(orderId));
    rerender({ id: otherId });
    expect(result.current.data).toBeUndefined();
    await waitFor(() => expect(result.current.data?.id).toBe(otherId));
  });

  it.each(['', ' ', 'invalid', 'ZT-12345678', 'EXT-1', `${orderId}/events`, ` ${orderId}`])(
    'disables invalid ID %j using the API validator', async (id) => {
      const adapter = vi.fn();
      apiClient.defaults.adapter = adapter;
      expect(isMerchantOrderId(id)).toBe(false);
      const { result } = renderHook(() => useMerchantOrderQuery(id), { wrapper });
      expect(result.current.fetchStatus).toBe('idle');
      await act(async () => { await result.current.refetch(); });
      expect(adapter).not.toHaveBeenCalled();
      expect(auth.refreshAccessToken).not.toHaveBeenCalled();
    },
  );

  it.each(['no-user', 'no-token', 'no-id', 'loading', 'disabled'])(
    'does not request details when %s', async (state) => {
      if (state === 'no-user') auth.user = null;
      if (state === 'no-token') auth.user!.accessToken = '';
      if (state === 'no-id') auth.user!.id = '';
      if (state === 'loading') auth.loading = true;
      const adapter = vi.fn();
      apiClient.defaults.adapter = adapter;
      const { result } = renderHook(() => useMerchantOrderQuery(orderId, state !== 'disabled'), { wrapper });
      expect(result.current.fetchStatus).toBe('idle');
      if (state !== 'disabled') await act(async () => { await result.current.refetch(); });
      expect(adapter).not.toHaveBeenCalled();
      expect(auth.refreshAccessToken).not.toHaveBeenCalled();
    },
  );

  it('starts once authentication finishes loading', async () => {
    auth.loading = true;
    apiClient.defaults.adapter = async (config) => success(config);
    const { result, rerender } = renderHook(() => useMerchantOrderQuery(orderId), { wrapper });
    expect(result.current.fetchStatus).toBe('idle');
    auth.loading = false;
    rerender();
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });

  it.each([401, 403, 404])('preserves HTTP %i without retry or private data', async (status) => {
    const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => {
      const response = { data: { title: 'Unavailable', detail: 'Order not found.' }, status, statusText: 'Error', headers: {}, config };
      throw new AxiosError('Request failed', 'ERR_BAD_REQUEST', config, undefined, response);
    });
    apiClient.defaults.adapter = adapter;
    const { result } = renderHook(() => useMerchantOrderQuery(orderId), { wrapper });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error).toMatchObject({ category: 'http', status });
    expect(result.current.data).toBeUndefined();
    expect(adapter).toHaveBeenCalledTimes(1);
    expect(auth.refreshAccessToken).toHaveBeenCalledTimes(1);
  });

  it.each(['network', 'unavailable'])('bounds retries for %s failures using shared defaults', async (failure) => {
    const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => {
      if (failure === 'network') throw new AxiosError('Network Error', 'ERR_NETWORK', config);
      throw new AxiosError('Unavailable', 'ERR_BAD_RESPONSE', config, undefined, {
        data: { title: 'Unavailable' }, status: 503, statusText: 'Unavailable', headers: {}, config,
      });
    });
    apiClient.defaults.adapter = adapter;
    const { result } = renderHook(() => useMerchantOrderQuery(orderId), { wrapper });
    await waitFor(() => expect(result.current.isError).toBe(true), { timeout: 3000 });
    expect(adapter).toHaveBeenCalledTimes(3);
    expect(result.current.data).toBeUndefined();
    expect(result.current.error).toMatchObject({ category: failure === 'network' ? 'network' : 'http' });
  });

  it('aborts the API request when the hook unmounts', async () => {
    let signal: AbortSignal | undefined;
    apiClient.defaults.adapter = (config) => new Promise((_, reject) => {
      signal = config.signal as AbortSignal;
      signal.addEventListener('abort', () => reject(new CanceledError('canceled', config)));
    });
    const { unmount } = renderHook(() => useMerchantOrderQuery(orderId), { wrapper });
    await waitFor(() => expect(signal).toBeDefined());
    unmount();
    expect(signal?.aborted).toBe(true);
    expect(client.getQueryState(merchantQueryKeys.order(orderId, 'merchant-a'))?.fetchStatus).toBe('idle');
  });

  it('does not expose the previous principal cache while the next principal is loading or denied', async () => {
    let denySecond!: () => void;
    apiClient.defaults.adapter = async (config) => {
      if (config.headers.Authorization === 'Bearer token-a') return success(config);
      return new Promise((_, reject) => {
        denySecond = () => reject(new AxiosError('Request failed', 'ERR_BAD_REQUEST', config, undefined, {
          data: { title: 'Not Found', detail: 'Order not found.' }, status: 404, statusText: 'Not Found', headers: {}, config,
        }));
      });
    };
    // Observe both fields during render so TanStack's tracked-property updates include errors.
    const { result, rerender } = renderHook(() => {
      const query = useMerchantOrderQuery(orderId);
      return { data: query.data, isError: query.isError };
    }, { wrapper });
    await waitFor(() => expect(result.current.data).toEqual(order));
    auth.user = { id: 'merchant-b', accessToken: 'token-b', roles: ['MERCHANT'] };
    rerender();
    expect(result.current.data).toBeUndefined();
    await waitFor(() => expect(denySecond).toBeDefined());
    await act(async () => { denySecond(); });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.data).toBeUndefined();
    expect(client.getQueryData(merchantQueryKeys.order(orderId, 'merchant-a'))).toEqual(order);
    expect(client.getQueryData(merchantQueryKeys.order(orderId, 'merchant-b'))).toBeUndefined();
    auth.user = null;
    rerender();
    expect(result.current.data).toBeUndefined();
  });
});
