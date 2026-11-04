import type { RouteObject } from 'react-router-dom';
import { CourierLayout } from './layout/CourierLayout';
import { TodayDeliveriesPage, DeliveryDetailPage, CourierProfilePage } from './pages';

export const portalRoutes: RouteObject[] = [
  {
    element: <CourierLayout />,
    children: [
      { index: true, element: <TodayDeliveriesPage /> },
      { path: 'profile', element: <CourierProfilePage /> },
      { path: 'deliveries/:deliveryId', element: <DeliveryDetailPage /> },
    ],
  },
];

