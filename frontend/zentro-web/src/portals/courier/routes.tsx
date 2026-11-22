import { NotificationHistory } from '@/shared/domain/notifications/NotificationBell';
import type { RouteObject } from 'react-router-dom';
import { CourierLayout } from './layout/CourierLayout';
import {
  TodayDeliveriesPage,
  DeliveryDetailPage,
  CourierRoutePage,
  CourierHistoryPage,
  CourierEarningsPage,
  CourierProfilePage,
} from './pages';

export const portalRoutes: RouteObject[] = [
  {
    element: <CourierLayout />,
    children: [
      { path: 'notifications', element: <NotificationHistory portal="COURIER" /> },
      { index: true, element: <TodayDeliveriesPage /> },
      { path: 'route', element: <CourierRoutePage /> },
      { path: 'history', element: <CourierHistoryPage /> },
      { path: 'earnings', element: <CourierEarningsPage /> },
      { path: 'profile', element: <CourierProfilePage /> },
      { path: 'deliveries/:deliveryId', element: <DeliveryDetailPage /> },
    ],
  },
];
