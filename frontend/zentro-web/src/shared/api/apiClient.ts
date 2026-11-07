import axios, { AxiosError, type AxiosRequestConfig, type AxiosResponse } from 'axios';
import { env } from '@/shared/config/env';
let token: string | undefined;
export const setAccessToken=(value:string|undefined) => { token=value; };
export const getAccessToken=() => token;
export const apiClient=axios.create({baseURL:env.VITE_API_BASE_URL,timeout:5000});

export type ApiProblem = {
  title?: string;
  detail?: string;
  fieldErrors?: Record<string, string>;
  invalidFields?: Record<string, string>;
  instance?: string;
};

export type ApiErrorCategory = 'http' | 'network' | 'timeout' | 'canceled';

/** Normalized transport error. It remains Axios-compatible for existing callers. */
export class ApiClientError extends AxiosError {
  readonly category: ApiErrorCategory;
  readonly problem: ApiProblem;
  readonly fieldErrors: Record<string, string>;

  constructor(error: AxiosError) {
    const status = error.response?.status;
    const raw = error.response?.data;
    const body = raw && typeof raw === 'object' ? raw as Record<string, unknown> : {};
    const fieldErrors = stringMap(body.fieldErrors);
    const invalidFields = stringMap(body.invalidFields);
    super(error.message, error.code, error.config, error.request, error.response);
    this.name = 'ApiClientError';
    this.status = status;
    this.category = error.code === 'ERR_CANCELED' ? 'canceled'
      : error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT' ? 'timeout'
      : status === undefined ? 'network' : 'http';
    this.problem = {
      ...(typeof body.title === 'string' ? { title: body.title } : {}),
      ...(typeof body.detail === 'string' ? { detail: body.detail } : {}),
      ...(Object.keys(fieldErrors).length ? { fieldErrors } : {}),
      ...(Object.keys(invalidFields).length ? { invalidFields } : {}),
      ...(typeof body.instance === 'string' ? { instance: body.instance } : {}),
    };
    this.fieldErrors = { ...fieldErrors, ...invalidFields };
  }
}

function stringMap(value: unknown): Record<string, string> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {};
  return Object.fromEntries(Object.entries(value).filter((entry): entry is [string, string] => typeof entry[1] === 'string'));
}

apiClient.interceptors.request.use((config) => {
  // Keep a request-specific token (for example, a freshly renewed token) instead
  // of replacing it with the last token stored by the auth provider.
  if (token && !config.headers.Authorization) config.headers.Authorization=`Bearer ${token}`;
  return config;
});
apiClient.interceptors.response.use(
  (response: AxiosResponse) => response,
  (error: unknown) => Promise.reject(axios.isAxiosError(error) && !(error instanceof ApiClientError)
    ? new ApiClientError(error)
    : error),
);

export function withBearerToken(tokenValue: string | undefined, config: AxiosRequestConfig = {}): AxiosRequestConfig {
  if (!tokenValue) return config;
  return { ...config, headers: { ...config.headers, Authorization: `Bearer ${tokenValue}` } };
}
export function friendlyApiError(error: unknown, fallback: string): string {
  if (!axios.isAxiosError(error)) return fallback;
  if (error.response?.status === 401) return 'Your session has expired. Please sign in again.';
  if (error.response?.status === 403) return 'Your account does not have permission to do that.';
  if (!error.response) return 'We could not reach Zentro. Check your connection and try again.';
  return fallback;
}
