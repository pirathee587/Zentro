import { apiClient, withBearerToken } from '@/shared/api/apiClient';
import type { Role } from '@/shared/config/roles';

export type NotificationItem = {
  id: string; type: string; title: string; message: string; parcelId: string;
  target: string; occurredAt: string; readAt: string | null;
};
export type InboxPage = { items: NotificationItem[]; unread: number; total: number; page: number };
export const notificationApi = {
  list: async (portal: Role, page: number, token: string, signal?: AbortSignal) =>
    (await apiClient.get<InboxPage>('/api/v1/notifications', withBearerToken(token, { params: { portal, page }, signal }))).data,
  read: async (portal: Role, id: string, token: string) => {
    await apiClient.post(`/api/v1/notifications/${encodeURIComponent(id)}/read`, null, withBearerToken(token, { params: { portal } }));
  },
  readAll: async (portal: Role, token: string) => {
    await apiClient.post('/api/v1/notifications/read-all', null, withBearerToken(token, { params: { portal } }));
  },
};
