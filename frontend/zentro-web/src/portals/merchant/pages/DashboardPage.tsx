import React from 'react';
import { Link, useLocation } from 'react-router-dom';
import { useAuth } from '@/shared/auth/useAuth';
import { friendlyApiError } from '@/shared/api/apiClient';
import { Button, ErrorState, Spinner } from '@/shared/ui';
import {
  useMerchantApiKeyQuery,
  useMerchantApplicationQuery,
  useMerchantOrdersQuery,
} from '../hooks/useMerchantQueries';
import type { MerchantOrderStatus } from '../api/merchantApi';
import { Zap, Check, AlertCircle } from 'lucide-react';

interface DisplayOrder {
  id: string;
  orderRef: string;
  destination: string;
  status: 'In transit' | 'Delivered' | 'Failed' | 'Created' | 'Picked up' | 'Out for delivery';
  webhook: 'Delivered' | 'None' | 'Retrying' | 'Failed';
}

const DEFAULT_RECENT_ORDERS: DisplayOrder[] = [
  { id: '1', orderRef: 'LT-5521', destination: 'Kandy', status: 'In transit', webhook: 'Delivered' },
  { id: '2', orderRef: 'LT-5520', destination: 'Galle', status: 'Delivered', webhook: 'Delivered' },
  { id: '3', orderRef: 'LT-5519', destination: 'Colombo 05', status: 'In transit', webhook: 'None' },
  { id: '4', orderRef: 'LT-5518', destination: 'Jaffna', status: 'Failed', webhook: 'Delivered' },
  { id: '5', orderRef: 'LT-5517', destination: 'Matara', status: 'Delivered', webhook: 'None' },
];

const orderStatusLabels: Record<MerchantOrderStatus, string> = {
  CREATED: 'Created',
  PICKED_UP: 'Picked up',
  IN_TRANSIT: 'In transit',
  OUT_FOR_DELIVERY: 'Out for delivery',
  DELIVERED: 'Delivered',
  FAILED: 'Failed',
};

function formatOrderStatus(status: MerchantOrderStatus): 'In transit' | 'Delivered' | 'Failed' | 'Created' | 'Picked up' | 'Out for delivery' {
  const label = orderStatusLabels[status] || status;
  if (label === 'In transit') return 'In transit';
  if (label === 'Delivered') return 'Delivered';
  if (label === 'Failed') return 'Failed';
  if (label === 'Created') return 'Created';
  if (label === 'Picked up') return 'Picked up';
  return 'Out for delivery';
}

export function DashboardPage() {
  const { user } = useAuth();
  const location = useLocation();
  const navigationMerchantStatus = (location.state as { merchantStatus?: string } | null)?.merchantStatus;
  const pendingReview =
    user?.roles.includes('CUSTOMER') === true &&
    (user.merchantStatus === 'PENDING' || navigationMerchantStatus === 'PENDING');
  const application = useMerchantApplicationQuery(!pendingReview);
  const key = useMerchantApiKeyQuery(!pendingReview);
  const orders = useMerchantOrdersQuery(!pendingReview);

  if (pendingReview) {
    return (
      <main className="sd-page-container">
        <section className="sd-card">
          <div className="sd-page-title-group">
            <span className="sd-badge-approved">Pending review</span>
            <h1 className="sd-page-title" style={{ marginTop: 12 }}>
              We&apos;re reviewing your details.
            </h1>
            <p className="sd-page-subtitle">
              Your merchant application is in the review queue. You can keep this dashboard open and check again later.
            </p>
          </div>
          <div style={{ marginTop: 20 }}>
            <Button variant="secondary" className="sd-btn-pill-light" type="button" onClick={() => window.location.reload()}>
              Refresh status
            </Button>
          </div>
        </section>
      </main>
    );
  }

  if (application.isLoading) {
    return (
      <main className="sd-page-container">
        <h1 className="sr-only">Merchant dashboard</h1>
        <div style={{ padding: 40, textAlign: 'center' }}>
          <Spinner label="Loading your business dashboard" />
        </div>
      </main>
    );
  }

  if (application.isError) {
    return (
      <main className="sd-page-container">
        <h1 className="sr-only">Merchant dashboard</h1>
        <ErrorState message={friendlyApiError(application.error, 'We could not load your business details.')} />
      </main>
    );
  }

  const businessName = application.data?.businessName || 'Lanka Threads';
  const hasOrdersFromApi = orders.data && orders.data.length > 0;

  return (
    <main className="sd-page-container" aria-label="Merchant Dashboard">
      {/* Page Header */}
      <div className="sd-page-header">
        <div className="sd-page-title-group">
          <h1 className="sd-page-title">Welcome back, {businessName}</h1>
          <div className="sd-title-badges-row">
            <span className="sd-badge-approved">Approved merchant</span>
            <span className="sd-title-mode-text">API v1 · Live mode</span>
          </div>
        </div>

        {/* Top Right Counters */}
        <div className="sd-header-stats-group">
          <div className="sd-header-stat">
            <span className="sd-stat-number">12</span>
            <span className="sd-stat-label">Orders this month</span>
          </div>
          <div className="sd-header-stat">
            <span className="sd-stat-number">50%</span>
            <span className="sd-stat-label">Delivered</span>
          </div>
          <div className="sd-header-stat">
            <span className="sd-stat-number">1</span>
            <span className="sd-stat-label">Failed</span>
          </div>
        </div>
      </div>

      {/* Main Grid */}
      <div className="sd-grid-2col" style={{ gap: 24 }}>
        {/* Top Left Card: Recent orders */}
        <section className="sd-card" aria-labelledby="recent-orders-heading">
          <div className="sd-card-header">
            <h2 id="recent-orders-heading" className="sd-card-title">
              Recent orders
            </h2>
            <Link to="/merchant/dashboard/orders" className="sd-text-link">
              View all
            </Link>
          </div>

          {/* Orders Query States */}
          {orders.isLoading && (
            <div className="merchant-orders-state" style={{ padding: 24, textAlign: 'center' }}>
              <Spinner label="Loading your orders" />
              <p className="sd-cell-mute" style={{ marginTop: 8 }}>Loading your orders…</p>
            </div>
          )}

          {orders.isError && (
            <div role="alert" style={{ padding: 20 }}>
              <p className="sd-cell-mute">We could not load your orders. Please try again.</p>
              <div style={{ marginTop: 12 }}>
                <Button variant="secondary" className="sd-btn-pill-light" type="button" onClick={() => void orders.refetch()}>
                  Retry
                </Button>
              </div>
            </div>
          )}

          {!orders.isLoading && !orders.isError && orders.data?.length === 0 && (
            <div style={{ padding: 24, textAlign: 'center' }}>
              <p className="sd-card-subtitle" style={{ fontSize: 14 }}>No orders yet.</p>
              <span className="sd-cell-mute">Orders created through your merchant integration will appear here.</span>
            </div>
          )}

          {/* Table */}
          {!orders.isLoading && !orders.isError && (orders.data === undefined || hasOrdersFromApi) && (
            <div className="sd-table-wrap">
              <table className="sd-table" aria-label="Recent orders table">
                <thead>
                  <tr>
                    <th scope="col" style={{ width: '22%' }}>Order</th>
                    <th scope="col" style={{ width: '28%' }}>Destination</th>
                    <th scope="col" style={{ width: '26%' }}>Status</th>
                    <th scope="col" style={{ width: '24%' }}>Webhook</th>
                  </tr>
                </thead>
                <tbody>
                  {hasOrdersFromApi
                    ? orders.data?.slice(0, 5).map((order) => {
                        const statusFormatted = formatOrderStatus(order.status);
                        return (
                          <tr key={order.id}>
                            <td className="sd-cell-bold">{order.externalOrderRef}</td>
                            <td>{order.trackingRef}</td>
                            <td>
                              {statusFormatted === 'In transit' && <span className="sd-badge-yellow">In transit</span>}
                              {statusFormatted === 'Delivered' && <span className="sd-badge-dark">Delivered</span>}
                              {statusFormatted === 'Failed' && <span className="sd-badge-coral">Failed</span>}
                              {statusFormatted === 'Created' && <span className="sd-badge-light">Created</span>}
                              {statusFormatted === 'Picked up' && <span className="sd-badge-yellow">Picked up</span>}
                              {statusFormatted === 'Out for delivery' && <span className="sd-badge-yellow">Out for delivery</span>}
                            </td>
                            <td>
                              <span className="sd-badge-dark">Delivered</span>
                            </td>
                          </tr>
                        );
                      })
                    : DEFAULT_RECENT_ORDERS.map((order) => (
                        <tr key={order.id}>
                          <td className="sd-cell-bold">{order.orderRef}</td>
                          <td>{order.destination}</td>
                          <td>
                            {order.status === 'In transit' && <span className="sd-badge-yellow">In transit</span>}
                            {order.status === 'Delivered' && <span className="sd-badge-dark">Delivered</span>}
                            {order.status === 'Failed' && <span className="sd-badge-coral">Failed</span>}
                          </td>
                          <td>
                            {order.webhook === 'Delivered' ? (
                              <span className="sd-badge-dark">Delivered</span>
                            ) : (
                              <span className="sd-cell-mute">None</span>
                            )}
                          </td>
                        </tr>
                      ))}
                </tbody>
              </table>
            </div>
          )}
        </section>

        {/* Top Right Card: Integration health */}
        <section className="sd-card" aria-labelledby="integration-health-heading">
          <div className="sd-card-header">
            <h2 id="integration-health-heading" className="sd-card-title">
              Integration health
            </h2>
          </div>

          <div className="sd-health-list">
            <div className="sd-health-row">
              <span className="sd-health-label">API key</span>
              <span className={key.data?.active === false ? 'sd-badge-coral' : 'sd-badge-dark'}>
                {key.data?.active === false ? 'Revoked' : 'Active'}
              </span>
            </div>
            <div className="sd-health-row">
              <span className="sd-health-label">Webhook endpoint</span>
              <span className="sd-health-val-code">hooks.lankathreads.lk</span>
            </div>
            <div className="sd-health-row">
              <span className="sd-health-label">Deliveries succeeded</span>
              <span className="sd-health-val">4 of 6</span>
            </div>
            <div className="sd-health-row">
              <span className="sd-health-label">Retries exhausted</span>
              <span className="sd-health-val">1</span>
            </div>
          </div>

          <div style={{ display: 'flex', gap: 12, marginTop: 28, flexWrap: 'wrap' }}>
            <Link to="/merchant/dashboard/api-keys" className="sd-btn-pill-light">
              Manage API key
            </Link>
            <Link to="/merchant/dashboard/webhooks" className="sd-btn-pill-light">
              Manage webhooks
            </Link>
          </div>
        </section>

        {/* Bottom Left Card: Orders by status */}
        <section className="sd-card" aria-labelledby="status-breakdown-heading">
          <div className="sd-card-header">
            <h2 id="status-breakdown-heading" className="sd-card-title">
              Orders by status
            </h2>
          </div>

          {/* Labels row */}
          <div className="sd-segment-labels-row">
            <span>Delivered</span>
            <span>In progress</span>
            <span>Created</span>
            <span>Failed</span>
          </div>

          {/* Segmented Bar */}
          <div className="sd-status-segments-bar">
            <div className="sd-segment-delivered" style={{ width: '50%' }}>
              50%
            </div>
            <div className="sd-segment-progress" style={{ width: '33%' }}>
              33%
            </div>
            <div className="sd-segment-created" style={{ width: '8.5%' }}>
              8%
            </div>
            <div className="sd-segment-failed" style={{ width: '8.5%' }}>
              8%
            </div>
          </div>

          <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', marginTop: 16 }}>
            <div>
              <div className="sd-card-subtitle" style={{ fontSize: 13 }}>
                Quoted delivery fees this month
              </div>
              <Link to="/merchant/dashboard/billing" className="sd-text-link" style={{ display: 'inline-block', marginTop: 6 }}>
                Open billing statement
              </Link>
            </div>
            <span style={{ fontSize: 24, fontWeight: 600, color: 'var(--ink)' }}>
              LKR 12,148
            </span>
          </div>
        </section>

        {/* Bottom Right Card: Latest webhook deliveries */}
        <section className="sd-card sd-dark-card" aria-labelledby="webhook-deliveries-heading">
          <div className="sd-card-header">
            <h2 id="webhook-deliveries-heading" className="sd-card-title sd-dark-title">
              Latest webhook deliveries
            </h2>
            <Link to="/merchant/dashboard/webhooks" className="sd-link-white">
              All deliveries
            </Link>
          </div>

          <div className="sd-webhook-event-list">
            {/* Item 1: Lightning bolt */}
            <div className="sd-webhook-event-item">
              <div className="sd-event-icon-wrap sd-event-icon-yellow" aria-hidden="true">
                <Zap size={16} fill="currentColor" />
              </div>
              <div className="sd-event-content">
                <div className="sd-event-title">
                  LT-5516: Status updated: Out for delivery (retrying)
                </div>
                <div className="sd-event-sub">Attempt 2 at 14:03:12</div>
              </div>
            </div>

            {/* Item 2: Checkmark */}
            <div className="sd-webhook-event-item">
              <div className="sd-event-icon-wrap sd-event-icon-dark" aria-hidden="true">
                <Check size={16} />
              </div>
              <div className="sd-event-content">
                <div className="sd-event-title">
                  LT-5521: Status updated: In transit (delivered)
                </div>
                <div className="sd-event-sub">Attempt 1 at 11:42:03</div>
              </div>
            </div>

            {/* Item 3: Exclamation */}
            <div className="sd-webhook-event-item">
              <div className="sd-event-icon-wrap sd-event-icon-dark" aria-hidden="true">
                <AlertCircle size={16} />
              </div>
              <div className="sd-event-content">
                <div className="sd-event-title">
                  LT-5515: Status updated: Picked up (failed)
                </div>
                <div className="sd-event-sub">Attempt 8 at 12:23:20</div>
              </div>
            </div>
          </div>
        </section>
      </div>
    </main>
  );
}
