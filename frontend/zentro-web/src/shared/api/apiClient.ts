import axios from 'axios'; import { env } from '@/shared/config/env';
let token: string | undefined;
export const setAccessToken=(value:string|undefined) => { token=value; };
export const getAccessToken=() => token;
export const apiClient=axios.create({baseURL:env.VITE_API_BASE_URL,timeout:5000});
apiClient.interceptors.request.use((config) => {
  // Keep a request-specific token (for example, a freshly renewed token) instead
  // of replacing it with the last token stored by the auth provider.
  if (token && !config.headers.Authorization) config.headers.Authorization=`Bearer ${token}`;
  return config;
});
export function friendlyApiError(error: unknown, fallback: string): string {
  if (!axios.isAxiosError(error)) return fallback;
  if (error.response?.status === 401) return 'Your session has expired. Please sign in again.';
  if (error.response?.status === 403) return 'Your account does not have permission to do that.';
  if (!error.response) return 'We could not reach Zentro. Check your connection and try again.';
  return fallback;
}
