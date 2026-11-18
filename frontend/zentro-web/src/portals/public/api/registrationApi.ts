import { apiClient } from '@/shared/api/apiClient';

export type SendOtpRequest = {
  email: string;
  name?: string;
  entryPoint?: string;
};

export type VerifyOtpRequest = {
  email: string;
  name: string;
  password: string;
  code: string;
  entryPoint?: string;
};

export type RegistrationResponse = {
  success: boolean;
  email: string;
  message: string;
};

export async function sendRegistrationOtp(request: SendOtpRequest): Promise<RegistrationResponse> {
  return (await apiClient.post<RegistrationResponse>('/api/v1/auth/register/send-otp', request, { timeout: 30000 })).data;
}

export async function verifyRegistrationOtp(request: VerifyOtpRequest): Promise<RegistrationResponse> {
  return (await apiClient.post<RegistrationResponse>('/api/v1/auth/register/verify-otp', request, { timeout: 30000 })).data;
}

export type ResetPasswordRequest = {
  email: string;
  code: string;
  newPassword: string;
};

export type VerifyResetOtpRequest = {
  email: string;
  code: string;
};

export async function sendForgotPasswordOtp(email: string): Promise<RegistrationResponse> {
  return (await apiClient.post<RegistrationResponse>('/api/v1/auth/forgot-password/send-otp', { email }, { timeout: 30000 })).data;
}

export async function verifyForgotPasswordOtp(request: VerifyResetOtpRequest): Promise<RegistrationResponse> {
  return (await apiClient.post<RegistrationResponse>('/api/v1/auth/forgot-password/verify-otp', request, { timeout: 30000 })).data;
}

export async function resetPasswordWithOtp(request: ResetPasswordRequest): Promise<RegistrationResponse> {
  return (await apiClient.post<RegistrationResponse>('/api/v1/auth/forgot-password/reset', request, { timeout: 30000 })).data;
}
