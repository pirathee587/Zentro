import React from 'react';
import { TrackView } from '../components/TrackView';
import { CustomerDashboard } from './CustomerDashboard';
import { SendParcelPage } from './SendParcelPage';
import { HistoryPage } from './HistoryPage';
import { ReceiptsPage } from './ReceiptsPage';
import { SupportPage } from './SupportPage';

export {
  CustomerDashboard,
  SendParcelPage,
  HistoryPage,
  ReceiptsPage,
  SupportPage,
};
export const CustomerHomePage = CustomerDashboard;


export const TrackParcelPage: React.FC = () => {
  return (
    <div>
      <div className="sd-page-header">
        <h1 className="sd-page-title">
          Track a parcel <span className="sr-only">Track Parcel</span>
        </h1>
        <p className="sd-page-subtitle">
          No login needed. Anyone with the tracking number can follow it, on any device.
        </p>
      </div>
      <TrackView />
    </div>
  );
};

// Aliases for backward compatibility
export const CreateDeliveryPage = SendParcelPage;
export const MyParcelsPage = HistoryPage;
export const ParcelDetailPage = TrackParcelPage;
export const ReceiptPage = ReceiptsPage;

