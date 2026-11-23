import axios from 'axios';
import { apiClient, friendlyApiError, withBearerToken } from '@/shared/api/apiClient';

export type CompletionState = 'PENDING' | 'COMPLETED' | 'REJECTED' | 'BLOCKED';
export type AssignmentStatus = 'PENDING_ACCEPTANCE' | 'ACCEPTED' | 'REJECTED';
export interface DeliverySummary {
  trackingRef?: string | null; parcelStatus?: string | null;
  parcelId: string; courierId: string; assignedAt: string;
  proofId: string | null; completionState: CompletionState | null;
  assignmentStatus?: AssignmentStatus; respondedAt?: string | null; rejectionReason?: string | null;
}
export interface SubmitProofRequest { recipientName: string; notes: string | null; proofReference: string }
export interface ProofResponse extends SubmitProofRequest {
  proofId: string; parcelId: string; submittedAt: string;
  completionState: CompletionState; completedAt: string | null;
}
export const proofLimits = { recipientName: 100, notes: 500, proofReference: 200 } as const;
const auth = (token: string, signal?: AbortSignal) => withBearerToken(token, signal ? { signal } : {});
const path = (id: string) => `/api/v1/courier/deliveries/${encodeURIComponent(id)}`;
export async function listDeliveries(token: string, signal?: AbortSignal): Promise<DeliverySummary[]> {
  return (await apiClient.get<DeliverySummary[]>('/api/v1/courier/deliveries', auth(token, signal))).data;
}
export async function getDelivery(id: string, token: string, signal?: AbortSignal): Promise<DeliverySummary> {
  return (await apiClient.get<DeliverySummary>(path(id), auth(token, signal))).data;
}
export async function getProof(id: string, token: string, signal?: AbortSignal): Promise<ProofResponse | null> {
  try { return (await apiClient.get<ProofResponse>(`${path(id)}/proof`, auth(token, signal))).data; }
  catch (error) {
    if (axios.isAxiosError(error) && error.response?.status === 404 && error.response.data?.code === 'PROOF_NOT_FOUND') return null;
    throw error;
  }
}
export async function submitProof(id: string, body: SubmitProofRequest, token: string): Promise<ProofResponse> {
  return (await apiClient.post<ProofResponse>(`${path(id)}/proof`, body, auth(token))).data;
}
export async function submitProofPhoto(id: string, body: SubmitProofRequest, photo: File, token: string, idempotencyKey: string, onProgress?: (value: number) => void): Promise<ProofResponse> {
  const form = new FormData(); form.append('recipientName', body.recipientName); if (body.notes) form.append('notes', body.notes); form.append('proofReference', body.proofReference); form.append('photo', photo);
  return (await apiClient.post<ProofResponse>(`${path(id)}/proof-photo`, form, { ...auth(token), headers: { Authorization: `Bearer ${token}`, 'Idempotency-Key': idempotencyKey }, onUploadProgress: event => onProgress?.(event.total ? Math.round(event.loaded * 100 / event.total) : 0) })).data;
}
export async function acceptDelivery(id: string, token: string): Promise<unknown> {
  return (await apiClient.post(`${path(id)}/accept`, {}, auth(token))).data;
}
export async function rejectDelivery(id: string, reason: string, token: string): Promise<unknown> {
  return (await apiClient.post(`${path(id)}/reject`, { reason }, auth(token))).data;
}
export async function updateDeliveryStatus(id: string, status: string, token: string, reason?: string, note?: string): Promise<unknown> {
  return (await apiClient.patch(`${path(id)}/status`, { status, reason, note }, auth(token))).data;
}
export function deliveryAccessDenied(error: unknown): boolean {
  return axios.isAxiosError(error) && [403, 404].includes(error.response?.status ?? 0);
}
export function deliverySessionExpired(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response?.status === 401;
}
/** A 404 from the new photo endpoint is not proof that courier ownership was lost. */
export function photoProofUploadUnavailable(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response?.status === 404;
}
export function deliveryError(error: unknown): string {
  if (axios.isAxiosError(error)) {
    if (error.response?.status === 403) return "You don't have access";
    if (error.response?.status === 409) return 'Proof is already recorded with different evidence. Refresh to view the saved proof.';
    if (error.response?.status === 404) return 'This delivery is no longer available to your account.';
    if (error.response?.status === 400) return 'Check the required fields and character limits.';
    if (error.response?.status === 503) {
      const code = error.response.data?.code;
      if (code === 'COURIER_VERIFICATION_FAILED') return 'We could not verify your courier access right now. Please try again later.';
      if (code === 'PROOF_STORAGE_UNAVAILABLE') return 'Delivery data is temporarily unavailable. Please try again later.';
      return 'Delivery services are temporarily unavailable. Please try again later.';
    }
  }
  return friendlyApiError(error, 'Could not load or save delivery information. Please try again.');
}
