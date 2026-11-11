/**
 * Typed API helpers for the Dispatcher portal.
 *
 * Backend contracts:
 *   GET    /api/v1/admin/users               – list users (DISPATCHER only)
 *   GET    /api/v1/admin/users/{userId}      – get single user (DISPATCHER only)
 *   PATCH  /api/v1/admin/users/{userId}      – update name / phone / accountStatus (DISPATCHER only)
 *   GET    /api/v1/admin/merchants           – list merchant applications (DISPATCHER only)
 *   PATCH  /api/v1/admin/merchants/{id}      – approve / reject application (DISPATCHER only)
 *
 * Authorization is enforced server-side via @PreAuthorize("hasRole('DISPATCHER')").
 * The UI must not bypass that by hiding or disabling UI controls only.
 */

import { apiClient } from '@/shared/api/apiClient';

// ─── User management ──────────────────────────────────────────────────────────

export type AdminUserProfile = {
  id: string;
  email: string;
  name: string | null;
  phone: string | null;
  enabled: boolean;
  accountStatus: 'ACTIVE' | 'DISABLED' | 'SUSPENDED';
  merchantStatus: 'PENDING' | 'APPROVED' | 'REJECTED' | null;
  roles: string[];
};

export type AdminUpdateUserRequest = {
  name?: string;
  phone?: string;
  /** Must be ACTIVE, DISABLED, or SUSPENDED. Backend enforces this. */
  accountStatus?: 'ACTIVE' | 'DISABLED' | 'SUSPENDED';
};

export type ListUsersParams = {
  status?: string;
  search?: string;
};

export async function listUsers(params: ListUsersParams = {}, signal?: AbortSignal): Promise<AdminUserProfile[]> {
  const searchParams = new URLSearchParams();
  if (params.status) searchParams.set('status', params.status);
  if (params.search) searchParams.set('search', params.search);
  const query = searchParams.toString();
  const url = `/api/v1/admin/users${query ? `?${query}` : ''}`;
  return (await apiClient.get<AdminUserProfile[]>(url, { signal })).data;
}

export async function getUser(userId: string): Promise<AdminUserProfile> {
  return (await apiClient.get<AdminUserProfile>(`/api/v1/admin/users/${userId}`)).data;
}

export async function updateUser(
  userId: string,
  request: AdminUpdateUserRequest,
): Promise<AdminUserProfile> {
  return (await apiClient.patch<AdminUserProfile>(`/api/v1/admin/users/${userId}`, request)).data;
}

// ─── Merchant management ───────────────────────────────────────────────────────

export type MerchantApplication = {
  id: string;
  appUserId: string;
  businessName: string;
  businessRegNo: string | null;
  contactPhone: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'SUSPENDED';
  rejectionReason: string | null;
  createdAt: string;
  reviewedAt: string | null;
};

export type ReviewDecision = 'APPROVED' | 'REJECTED' | 'SUSPENDED';

export type ReviewMerchantRequest = {
  decision: ReviewDecision;
  rejectionReason?: string;
};

export async function listMerchantApplications(
  statusOrSignal?: string | AbortSignal,
  signal?: AbortSignal,
): Promise<MerchantApplication[]> {
  let status: string | undefined;
  let abortSignal: AbortSignal | undefined;
  if (statusOrSignal instanceof AbortSignal) {
    abortSignal = statusOrSignal;
  } else if (typeof statusOrSignal === 'string') {
    status = statusOrSignal;
    abortSignal = signal;
  } else {
    abortSignal = signal;
  }
  const query = status && status !== 'ALL' ? `?status=${encodeURIComponent(status)}` : '';
  return (await apiClient.get<MerchantApplication[]>(`/api/v1/admin/merchants${query}`, { signal: abortSignal })).data;
}

export async function reviewMerchantApplication(
  applicationId: string,
  request: ReviewMerchantRequest,
): Promise<MerchantApplication> {
  return (
    await apiClient.patch<MerchantApplication>(`/api/v1/admin/merchants/${applicationId}`, request)
  ).data;
}

export async function suspendMerchantApplication(
  applicationId: string,
  reason: string,
): Promise<MerchantApplication> {
  return (
    await apiClient.patch<MerchantApplication>(`/api/v1/admin/merchants/${applicationId}/suspend`, { reason })
  ).data;
}

export async function reinstateMerchantApplication(
  applicationId: string,
): Promise<MerchantApplication> {
  return (
    await apiClient.patch<MerchantApplication>(`/api/v1/admin/merchants/${applicationId}/reinstate`)
  ).data;
}
