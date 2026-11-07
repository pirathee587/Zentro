import axios from 'axios';
import { apiClient, friendlyApiError, withBearerToken } from '@/shared/api/apiClient';

export type CompletionState = 'PENDING' | 'COMPLETED' | 'REJECTED' | 'BLOCKED';
export interface DeliverySummary {
  parcelId: string; courierId: string; assignedAt: string;
  proofId: string | null; completionState: CompletionState | null;
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
export function deliveryError(error: unknown): string {
  if (axios.isAxiosError(error)) {
    if (error.response?.status === 409) return 'Proof is already recorded with different evidence. Refresh to view the saved proof.';
    if (error.response?.status === 404) return 'This delivery is no longer available to your account.';
    if (error.response?.status === 400) return 'Check the required fields and character limits.';
    if (error.response?.status === 503) return 'Delivery services are unavailable. Your entries are preserved; refresh before retrying.';
  }
  return friendlyApiError(error, 'Could not load or save delivery information. Please try again.');
}
