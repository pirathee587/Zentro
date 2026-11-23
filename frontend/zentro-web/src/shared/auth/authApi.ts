import { apiClient } from '@/shared/api/apiClient';

export type LoginRequest = {
  email: string;
  password: string;
};

export type TokenResponse = {
  access_token: string;
  id_token?: string;
  refresh_token?: string;
  token_type: string;
  expires_in: number;
  scope?: string;
};

export async function loginWithPassword(request: LoginRequest): Promise<TokenResponse> {
  return (await apiClient.post<TokenResponse>('/api/v1/auth/login', request, { timeout: 30000 })).data;
}
