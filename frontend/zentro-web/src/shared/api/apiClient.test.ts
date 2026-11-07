import { afterEach, describe, expect, it, vi } from 'vitest';
import axios, { AxiosError, type AxiosAdapter } from 'axios';
import { ApiClientError, apiClient, friendlyApiError, getAccessToken, setAccessToken, withBearerToken } from './apiClient';

const originalAdapter = apiClient.defaults.adapter;
afterEach(() => {
  apiClient.defaults.adapter = originalAdapter;
  setAccessToken(undefined);
  vi.restoreAllMocks();
});

describe('shared API client', () => {
  it('uses the configured base URL and existing timeout', () => {
    expect(apiClient.defaults.baseURL).toBeTruthy();
    expect(apiClient.defaults.timeout).toBe(5_000);
  });

  it('attaches the current bearer token and supports a request-specific token', async () => {
    let authorization = '';
    apiClient.defaults.adapter = (async (config) => {
      authorization = String(config.headers.Authorization ?? '');
      return { data: {}, status: 200, statusText: 'OK', headers: {}, config };
    }) as AxiosAdapter;
    setAccessToken('shared-token');
    await apiClient.get('/test');
    expect(authorization).toBe('Bearer shared-token');
    expect(getAccessToken()).toBe('shared-token');
    expect(withBearerToken('fresh-token').headers).toMatchObject({ Authorization: 'Bearer fresh-token' });
  });

  it('maps network and timeout failures to normalized categories', async () => {
    apiClient.defaults.adapter = (async (config) => {
      throw new AxiosError('Network Error', 'ERR_NETWORK', config);
    }) as AxiosAdapter;
    await expect(apiClient.get('/test')).rejects.toMatchObject({ category: 'network' });

    apiClient.defaults.adapter = (async (config) => {
      throw new AxiosError('timeout', 'ECONNABORTED', config);
    }) as AxiosAdapter;
    await expect(apiClient.get('/test')).rejects.toMatchObject({ category: 'timeout' });
  });

  it('maps problem details and remains Axios-compatible', async () => {
    apiClient.defaults.adapter = (async (config) => {
      const response = { data: { title: 'Invalid request', detail: 'Check the form', fieldErrors: { phone: 'Invalid' }, invalidFields: { address: 'Required' }, instance: '/api/test' }, status: 422, statusText: 'Unprocessable Entity', headers: {}, config };
      throw new AxiosError('Request failed', 'ERR_BAD_REQUEST', config, undefined, response);
    }) as AxiosAdapter;
    const failure = await apiClient.get('/test').catch((error: unknown) => error);
    expect(failure).toBeInstanceOf(ApiClientError);
    expect(axios.isAxiosError(failure)).toBe(true);
    expect(failure).toMatchObject({ category: 'http', status: 422, problem: { title: 'Invalid request', detail: 'Check the form', instance: '/api/test' }, fieldErrors: { phone: 'Invalid', address: 'Required' } });
  });

  it('passes AbortSignal through and exposes cancellation as a normalized error', async () => {
    apiClient.defaults.adapter = (config) => new Promise((_, reject) => {
      config.signal?.addEventListener('abort', () => reject(new axios.CanceledError('canceled', config)));
    });
    const controller = new AbortController();
    const pending = apiClient.get('/test', { signal: controller.signal }).catch((error: unknown) => error);
    controller.abort();
    await expect(pending).resolves.toMatchObject({ category: 'canceled' });
  });

  it('preserves safe auth and connection messages without logging response data', () => {
    const log = vi.spyOn(console, 'log');
    const warn = vi.spyOn(console, 'warn');
    const error = vi.spyOn(console, 'error');
    const axiosFailure = (status?: number) => Object.assign(new Error('request failed'), {
      isAxiosError: true,
      ...(status === undefined ? {} : { response: { status } }),
    });
    expect(friendlyApiError(axiosFailure(401), 'Fallback')).toMatch(/session has expired/i);
    expect(friendlyApiError(axiosFailure(403), 'Fallback')).toMatch(/does not have permission/i);
    expect(friendlyApiError(axiosFailure(), 'Fallback')).toMatch(/could not reach Zentro/i);
    expect(log).not.toHaveBeenCalled();
    expect(warn).not.toHaveBeenCalled();
    expect(error).not.toHaveBeenCalled();
  });
});
