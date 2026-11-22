import { apiClient, withBearerToken } from '@/shared/api/apiClient';

export type ProofReviewStatus = 'PENDING_REVIEW' | 'COMPLETION_PENDING' | 'COMPLETION_PROCESSING' | 'REJECTED' | 'COMPLETED' | 'COMPLETION_FAILED' | 'BLOCKED';
export type ProofReview = { id: string; revisionId: string; proofId: string; parcelId: string; decision: ProofReviewStatus; version: number; recipientName: string; notes?: string | null; proofReference?: string | null; courierName?: string | null; submittedAt: string; rejectionReason?: string | null; completionErrorCode?: string | null };

export async function listProofReviews(status: ProofReviewStatus, token: string) {
  return (await apiClient.get<ProofReview[]>(`/api/v1/dispatch/proof-reviews?state=${status}`, withBearerToken(token))).data;
}
export async function decideProofReview(review: ProofReview, decision: 'approve' | 'reject', token: string, reason?: string) {
  const body = decision === 'reject' ? { version: review.version, reason } : { version: review.version };
  return (await apiClient.post<ProofReview>(`/api/v1/dispatch/proof-reviews/${review.id}/${decision}`, body, withBearerToken(token))).data;
}
export async function completeProofReview(reviewId: string, token: string) {
  return apiClient.post(`/api/v1/dispatch/proof-reviews/${reviewId}/complete`, {}, withBearerToken(token));
}
export async function proofPhotoUrl(reviewId: string, token: string) {
  return (await apiClient.get<{ url: string }>(`/api/v1/dispatch/proof-reviews/${reviewId}/photo-url`, withBearerToken(token))).data.url;
}
