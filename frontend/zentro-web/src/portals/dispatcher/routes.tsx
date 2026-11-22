import { NotificationHistory } from '@/shared/domain/notifications/NotificationBell';
import type { RouteObject } from 'react-router-dom';
import { DispatcherLayout } from './layout/DispatcherLayout';
import { DispatchQueuePage } from './pages/DispatchQueuePage';
import { ProofReviewsPage } from './pages/ProofReviewsPage';
import {
  DispatchControlPage,
  ParcelsPage,
  WorkloadPage,
  ReportsPage,
  UsersPage,
  MerchantApprovalsPage,
  ZonesPricingPage,
  CouriersPage,
  IntegrationAttemptsPage,
} from './pages';

export const portalRoutes: RouteObject[] = [
  {
    element: <DispatcherLayout />,
    children: [
      { path: 'notifications', element: <NotificationHistory portal="DISPATCHER" /> },
      { index: true, element: <DispatchControlPage /> },
      { path: 'dashboard', element: <DispatchControlPage /> },
      { path: 'parcels', element: <ParcelsPage /> },
      { path: 'queue', element: <DispatchQueuePage /> },
      { path: 'couriers', element: <CouriersPage /> },
      { path: 'workload', element: <WorkloadPage /> },
      { path: 'reports', element: <ReportsPage /> },
      { path: 'users', element: <UsersPage /> },
      { path: 'merchant-approvals', element: <MerchantApprovalsPage /> },
      { path: 'partner-approvals', element: <MerchantApprovalsPage /> },
      { path: 'zones-pricing', element: <ZonesPricingPage /> },
      { path: 'integration-attempts', element: <IntegrationAttemptsPage /> },
      { path: 'proof-reviews', element: <ProofReviewsPage /> },
    ],
  },
];
