import { apiClient } from '@/shared/api/apiClient';
import type { CourierProfile, VehicleType } from '@/portals/courier/api/courierApi';

export interface ListCouriersParams {
  status?: string;
  active?: boolean;
  zone?: string;
  search?: string;
}

export interface UpdateCourierAdminRequest {
  active?: boolean;
  assignedZone?: string;
  vehicleType?: VehicleType | string;
  name?: string;
  phone?: string;
}

/**
 * Lists all registered couriers for dispatchers.
 */
export async function listCouriersAdmin(
  params?: ListCouriersParams,
  token?: string
): Promise<CourierProfile[]> {
  const headers = token ? { Authorization: `Bearer ${token}` } : undefined;
  const res = await apiClient.get<CourierProfile[]>('/api/v1/admin/couriers', {
    params,
    headers,
  });
  return res.data;
}

/**
 * Retrieves a single courier profile by courier ID or user ID.
 */
export async function getCourierAdmin(
  courierId: string,
  token?: string
): Promise<CourierProfile> {
  const headers = token ? { Authorization: `Bearer ${token}` } : undefined;
  const res = await apiClient.get<CourierProfile>(`/api/v1/admin/couriers/${courierId}`, {
    headers,
  });
  return res.data;
}

/**
 * Updates a courier profile (active eligibility status, assigned zone, vehicle type, name, phone).
 */
export async function updateCourierAdmin(
  courierId: string,
  data: UpdateCourierAdminRequest,
  token?: string
): Promise<CourierProfile> {
  const headers = token ? { Authorization: `Bearer ${token}` } : undefined;
  const res = await apiClient.patch<CourierProfile>(`/api/v1/admin/couriers/${courierId}`, data, {
    headers,
  });
  return res.data;
}
