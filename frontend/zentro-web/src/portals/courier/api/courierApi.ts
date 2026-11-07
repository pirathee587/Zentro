import { apiClient, withBearerToken } from '@/shared/api/apiClient';

export type VehicleType = 'BICYCLE' | 'MOTORCYCLE' | 'CAR' | 'VAN' | 'TRUCK';

export interface CourierProfile {
  id: string;
  userId: string;
  email: string;
  name: string;
  phone: string;
  active: boolean;
  accountStatus: string;
  assignedZone: string | null;
  vehicleType: VehicleType | string | null;
  createdAt: string;
  updatedAt: string;
}

export interface UpdateCourierPersonalRequest {
  name?: string;
  phone?: string;
}

/**
 * Fetches the current courier's profile.
 * Targets the S1 endpoint via gateway.
 */
export async function getCourierProfile(token?: string, signal?: AbortSignal): Promise<CourierProfile> {
  const auth = withBearerToken(token);
  try {
    const res = await apiClient.get<CourierProfile>('/api/v1/users/courier-profile', { ...auth, signal });
    return res.data;
  } catch {
    // Fallback to /api/v1/courier/profile if gateway is configured directly
    const res = await apiClient.get<CourierProfile>('/api/v1/courier/profile', { ...auth, signal });
    return res.data;
  }
}

/**
 * Explicitly provisions the courier profile for the authenticated courier.
 */
export async function provisionCourierProfile(token?: string): Promise<CourierProfile> {
  const auth = withBearerToken(token);
  try {
    const res = await apiClient.post<CourierProfile>('/api/v1/courier/profile/provision', {}, auth);
    return res.data;
  } catch {
    const res = await apiClient.post<CourierProfile>('/api/v1/users/courier-profile/provision', {}, auth);
    return res.data;
  }
}

/**
 * Updates the courier's personal contact details (name and phone).
 */
export async function updateCourierPersonalInfo(
  payload: UpdateCourierPersonalRequest,
  token?: string
): Promise<void> {
  await apiClient.patch('/api/v1/users/me', payload, withBearerToken(token));
}
