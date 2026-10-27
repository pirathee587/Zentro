import type { RouteObject } from 'react-router-dom';
import { Navigate } from 'react-router-dom';
import { CustomerLayout } from './layout/CustomerLayout';
import {
  CustomerHomePage,
  SendParcelPage,
  TrackParcelPage,
  HistoryPage,
  ReceiptsPage,
  SupportPage,
} from './pages';

export const portalRoutes: RouteObject[] = [
  {
    element: <CustomerLayout />,
    children: [
      { index: true, element: <CustomerHomePage /> },
      { path: 'send', element: <SendParcelPage /> },
      { path: 'track', element: <TrackParcelPage /> },
      { path: 'track/:trackingRef', element: <TrackParcelPage /> },
      { path: 'history', element: <HistoryPage /> },
      { path: 'receipts', element: <ReceiptsPage /> },
      { path: 'support', element: <SupportPage /> },
      // Backward compatibility routes
      { path: 'create-delivery', element: <Navigate to="/customer/send" replace /> },
      { path: 'parcels', element: <Navigate to="/customer/history" replace /> },
      { path: 'parcels/:parcelId', element: <Navigate to="/customer/track" replace /> },
      { path: 'parcels/:parcelId/receipt', element: <Navigate to="/customer/receipts" replace /> },
    ],
  },
];
