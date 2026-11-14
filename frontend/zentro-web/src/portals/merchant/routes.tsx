import type { RouteObject } from 'react-router-dom';
import { MerchantLayout } from './layout/MerchantLayout';
import {
  RegisterPage,
  DashboardPage,
  OrdersPage,
  ApiKeysPage,
  ApiCredentialsPage,
  WebhooksPage,
  BillingPage,
} from './pages';

export const portalRoutes: RouteObject[] = [
  {
    element: <MerchantLayout />,
    children: [
      { path: 'register', element: <RegisterPage /> },
      { index: true, element: <DashboardPage /> },
      { path: 'orders', element: <OrdersPage /> },
      { path: 'api-keys', element: <ApiKeysPage /> },
      { path: 'api-credentials', element: <ApiCredentialsPage /> },
      { path: 'webhooks', element: <WebhooksPage /> },
      { path: 'billing', element: <BillingPage /> },
    ],
  },
];
