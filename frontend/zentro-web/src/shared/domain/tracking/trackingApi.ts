import { apiClient } from '@/shared/api/apiClient'; export const getTracking=(trackingNumber:string)=>apiClient.get(`/api/v1/tracking/${trackingNumber}`);
