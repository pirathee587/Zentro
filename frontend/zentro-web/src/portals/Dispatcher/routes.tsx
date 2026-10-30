import type { RouteObject } from 'react-router-dom';
import { AdminLayout } from './layout/AdminLayout';
import {
  UnassignedParcelsPage,
  WorkloadPage,
  ReportsPage,
  UsersPage,
  MerchantApprovalsPage,
  ZonesPricingPage,
  CouriersPage,
} from './pages';

export const portalRoutes: RouteObject[] = [
  {
    element: <AdminLayout />,
    children: [
      { index: true, element: <UnassignedParcelsPage /> },
      { path: 'couriers', element: <CouriersPage /> },
      { path: 'workload', element: <WorkloadPage /> },
      { path: 'reports', element: <ReportsPage /> },
      { path: 'users', element: <UsersPage /> },
      { path: 'merchant-approvals', element: <MerchantApprovalsPage /> },
      { path: 'zones-pricing', element: <ZonesPricingPage /> },
    ],
  },
];

