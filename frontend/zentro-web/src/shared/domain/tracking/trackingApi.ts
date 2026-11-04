import { apiClient } from '@/shared/api/apiClient';

export interface TrackingMilestone {
  id?: string;
  title: string;
  description?: string;
  timestamp?: string;
  formattedDate?: string;
  status: string;
  location?: string;
  failureReason?: string;
}

export interface TrackingEvent {
  id?: string;
  trackingRef: string;
  status: string;
  title: string;
  description?: string;
  actorRole?: string;
  location?: string;
  occurredAt: string;
  failureReason?: string;
}

/**
 * Approved S4 PublicTrackingResponse contract.
 * Excludes all sensitive customer data (names, phones, full addresses, fees).
 */
export interface PublicTrackingResponse {
  trackingRef: string;
  status: string;
  rawStatus: string;
  category?: string;
  size?: string;
  description?: string;
  originCity: string;
  destinationCity: string;
  estimatedDays?: number;
  estimatedDeliveryDate?: string;
  createdAt?: string;
  deliveredAt?: string;
  failureReason?: string;
  events?: TrackingEvent[];
  timeline?: TrackingMilestone[];
}

export const getTracking = (trackingNumber: string) =>
  apiClient.get<PublicTrackingResponse>(`/api/v1/tracking/${encodeURIComponent(trackingNumber.trim().toUpperCase())}`);

export async function fetchPublicTracking(trackingNumber: string): Promise<PublicTrackingResponse | null> {
  const clean = trackingNumber.trim().toUpperCase();
  if (!clean) return null;
  try {
    const { data } = await getTracking(clean);
    return data;
  } catch (error: unknown) {
    const status = (error as { response?: { status?: number } })?.response?.status;
    if (status === 404) return null;
    throw error;
  }
}
