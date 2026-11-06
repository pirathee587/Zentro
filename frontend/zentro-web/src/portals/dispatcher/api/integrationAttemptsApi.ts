import { apiClient } from '@/shared/api/apiClient';

export type IntegrationAttempt = {
  id: string;
  eventId: string;
  parcelId: string;
  eventType: string;
  status: string;
  attempts: number;
  lastError: string | null;
  nextAttemptAt: string | null;
  createdAt: string;
  updatedAt: string;
  trackingRef?: string;
  parcelStatus?: string;
  channel?: string;
  responseStatus?: number | null;
};

type HistoryResponse = {
  notifications: Array<IntegrationAttempt>;
  webhooks: Array<IntegrationAttempt>;
  page: number;
  size: number;
  totalNotifications: number;
  totalWebhooks: number;
};

export async function getIntegrationAttempts(page = 0, size = 25): Promise<HistoryResponse> {
  const [notifications, webhooks] = await Promise.all([
    apiClient.get<HistoryResponse>('/api/v1/admin/notification-attempts', { params: { page, size } }),
    apiClient.get<HistoryResponse>('/api/v1/admin/webhook-deliveries', { params: { page, size } }),
  ]);
  return {
    notifications: notifications.data.notifications,
    webhooks: webhooks.data.webhooks,
    page,
    size,
    totalNotifications: notifications.data.totalNotifications,
    totalWebhooks: webhooks.data.totalWebhooks,
  };
}
