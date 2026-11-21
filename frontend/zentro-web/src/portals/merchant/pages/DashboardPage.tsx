import React from 'react';
import { Link, useLocation } from 'react-router-dom';

import { useAuth } from '@/shared/auth/useAuth';
import { friendlyApiError } from '@/shared/api/apiClient';
import { Button, ErrorState, Spinner } from '@/shared/ui';
import {
  useMerchantApiKeyQuery,
  useMerchantApplicationQuery,
  useMerchantOrdersQuery,
  useMerchantOrderSummaryQuery,
  useMerchantBillingQuery,
  useMerchantWebhookQuery,
  useMerchantWebhookDeliveriesQuery,
} from '../hooks/useMerchantQueries';
import type { MerchantOrderStatus } from '../api/merchantApi';

const orderStatusLabels: Record<MerchantOrderStatus, string> = {
  CREATED: 'Created',
  PICKED_UP: 'Picked up',
  IN_TRANSIT: 'In transit',
  OUT_FOR_DELIVERY: 'Out for delivery',
  DELIVERED: 'Delivered',
  FAILED: 'Failed',
};

function currentBillingMonth() {
  const now = new Date();
  return `${now.getUTCFullYear()}-${String(now.getUTCMonth() + 1).padStart(2, '0')}`;
}

export function DashboardPage() {
  const { user } = useAuth();
  const location = useLocation();
  const navigationMerchantStatus =
    (location.state as { merchantStatus?: string } | null)?.merchantStatus;
  const pendingReview =
    user?.roles.includes('CUSTOMER') === true &&
    (user.merchantStatus === 'PENDING' || navigationMerchantStatus === 'PENDING');

  const application = useMerchantApplicationQuery(!pendingReview);
  const key = useMerchantApiKeyQuery(!pendingReview);
  const orders = useMerchantOrdersQuery(!pendingReview);
  const summary = useMerchantOrderSummaryQuery(!pendingReview);
  const billing = useMerchantBillingQuery(currentBillingMonth(), !pendingReview);
  const webhook = useMerchantWebhookQuery(!pendingReview);
  const webhookDeliveries = useMerchantWebhookDeliveriesQuery(!pendingReview);

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
              Your merchant application is in the review queue. You can keep this
              dashboard open and check again later.
            </p>
          </div>
          <div style={{ marginTop: 20 }}>
            <Button
              variant="secondary"
              className="sd-btn-pill-light"
              type="button"
              onClick={() => window.location.reload()}
            >
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
        <ErrorState
          message={friendlyApiError(
            application.error,
            'We could not load your business details.',
          )}
        />
      </main>
    );
  }

  const businessName = application.data?.businessName || 'Merchant';
  const orderList = orders.data ?? [];
  const totalOrders = summary.data?.totalOrders ?? 0;
  const deliveredOrders = summary.data?.deliveredOrders ?? 0;
  const failedOrders = summary.data?.failedOrders ?? 0;
  const createdOrders = summary.data?.statusCounts.CREATED ?? 0;
  const inProgressOrders = ['PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY'].reduce((n, status) => n + (summary.data?.statusCounts[status] ?? 0), 0);
  const deliveredPercentage = totalOrders > 0
    ? Math.round((deliveredOrders / totalOrders) * 100)
    : 0;
  const statusSegments = [
    { label: 'Delivered', count: deliveredOrders, className: 'sd-segment-delivered' },
    { label: 'In progress', count: inProgressOrders, className: 'sd-segment-progress' },
    { label: 'Created', count: createdOrders, className: 'sd-segment-created' },
    { label: 'Failed', count: failedOrders, className: 'sd-segment-failed' },
  ];

  const apiKeyLabel = key.isLoading
    ? 'Loading…'
    : key.isError
      ? 'Unavailable'
      : !key.data
        ? 'Not configured'
        : key.data.active
          ? 'Active'
          : 'Revoked';

  return (
    <main className="sd-page-container" aria-label="Merchant Dashboard">
      <div className="sd-page-header">
        <div className="sd-page-title-group">
          <h1 className="sd-page-title">Welcome back, {businessName}</h1>
          <div className="sd-title-badges-row">
            <span className="sd-badge-approved">Approved merchant</span>
            <span className="sd-title-mode-text">API v1</span>
          </div>
        </div>

        <div className="sd-header-stats-group">
          <div className="sd-header-stat">
            <span className="sd-stat-number">{summary.isSuccess ? totalOrders : '—'}</span>
            <span className="sd-stat-label">Orders this month</span>
          </div>
          <div className="sd-header-stat">
            <span className="sd-stat-number">
              {summary.isSuccess ? `${deliveredPercentage}%` : '—'}
            </span>
            <span className="sd-stat-label">Delivered this month</span>
          </div>
          <div className="sd-header-stat">
            <span className="sd-stat-number">{summary.isSuccess ? failedOrders : '—'}</span>
            <span className="sd-stat-label">Failed this month</span>
          </div>
        </div>
      </div>

      <div className="sd-grid-2col" style={{ gap: 24 }}>
        <section className="sd-card" aria-labelledby="recent-orders-heading">
          <div className="sd-card-header">
            <h2 id="recent-orders-heading" className="sd-card-title">Recent orders</h2>
            <Link to="/merchant/dashboard/orders" className="sd-text-link">View all</Link>
          </div>

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
                <Button
                  variant="secondary"
                  className="sd-btn-pill-light"
                  type="button"
                  onClick={() => void orders.refetch()}
                >
                  Retry
                </Button>
              </div>
            </div>
          )}
          {orders.isSuccess && orderList.length === 0 && (
            <div style={{ padding: 24, textAlign: 'center' }}>
              <p className="sd-card-subtitle" style={{ fontSize: 14 }}>No orders yet.</p>
              <span className="sd-cell-mute">
                Orders created through your merchant integration will appear here.
              </span>
            </div>
          )}
          {orders.isSuccess && orderList.length > 0 && (
            <div className="sd-table-wrap">
              <table className="sd-table" aria-label="Recent orders table">
                <thead>
                  <tr>
                    <th scope="col" style={{ width: '30%' }}>Order</th>
                    <th scope="col" style={{ width: '36%' }}>Tracking reference</th>
                    <th scope="col" style={{ width: '34%' }}>Status</th>
                  </tr>
                </thead>
                <tbody>
                  {orderList.slice(0, 5).map((order) => (
                    <tr key={order.id}>
                      <td className="sd-cell-bold">{order.externalOrderRef || order.id}</td>
                      <td>{order.trackingRef || '—'}</td>
                      <td>
                        <span
                          className={order.status === 'DELIVERED'
                            ? 'sd-badge-dark'
                            : order.status === 'FAILED'
                              ? 'sd-badge-coral'
                              : order.status === 'CREATED'
                                ? 'sd-badge-light'
                                : 'sd-badge-yellow'}
                        >
                          {orderStatusLabels[order.status] || order.status}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>

        <section className="sd-card" aria-labelledby="integration-health-heading">
          <div className="sd-card-header">
            <h2 id="integration-health-heading" className="sd-card-title">Integration health</h2>
          </div>
          <div className="sd-health-list">
            <div className="sd-health-row">
              <span className="sd-health-label">API key</span>
              <span className={key.data?.active === true ? 'sd-badge-dark' : 'sd-badge-coral'}>
                {apiKeyLabel}
              </span>
            </div>
            <div className="sd-health-row">
              <span className="sd-health-label">Webhook endpoint</span>
              <span className="sd-health-val-code">{webhook.isLoading ? 'Loading…' : webhook.isError ? 'Unavailable' : webhook.data?.active ? webhook.data.url : 'Not configured'}</span>
            </div>
            <div className="sd-health-row">
              <span className="sd-health-label">Deliveries succeeded</span>
              <span className="sd-health-val">{webhookDeliveries.isSuccess ? webhookDeliveries.data.items.filter(item => item.status === 'DELIVERED' || item.status === 'SUCCEEDED').length : 'Unavailable'}</span>
            </div>
            <div className="sd-health-row">
              <span className="sd-health-label">Retries exhausted</span>
              <span className="sd-health-val">{webhookDeliveries.isSuccess ? webhookDeliveries.data.items.filter(item => item.status === 'FAILED' || item.status === 'DEAD').length : 'Unavailable'}</span>
            </div>
          </div>
          <p className="sd-cell-mute" style={{ marginTop: 12 }}>
            Delivery counts reflect the latest {webhookDeliveries.data?.items.length ?? 0} attempts returned by the API.
          </p>
          <div style={{ display: 'flex', gap: 12, marginTop: 28, flexWrap: 'wrap' }}>
            <Link to="/merchant/dashboard/api-keys" className="sd-btn-pill-light">
              Manage API key
            </Link>
            <Link to="/merchant/dashboard/webhooks" className="sd-btn-pill-light">
              Manage webhooks
            </Link>
          </div>
        </section>

        <section className="sd-card" aria-labelledby="status-breakdown-heading">
          <div className="sd-card-header">
            <h2 id="status-breakdown-heading" className="sd-card-title">Orders by status</h2>
          </div>
          <div className="sd-segment-labels-row">
            <span>Delivered</span>
            <span>In progress</span>
            <span>Created</span>
            <span>Failed</span>
          </div>
          {summary.isLoading && <Spinner label="Loading order statuses" />}
          {summary.isError && (
            <div role="alert">
              <p className="sd-cell-mute">Could not load order statuses.</p>
              <Button type="button" variant="secondary" onClick={() => void summary.refetch()}>Retry order summary</Button>
            </div>
          )}
          {summary.isSuccess && totalOrders === 0 && (
            <p className="sd-cell-mute" style={{ marginTop: 18 }}>No order status data yet.</p>
          )}
          {summary.isSuccess && totalOrders > 0 && (
            <>
              <div className="sd-status-segments-bar" aria-label="Status distribution for this month">
                {statusSegments.filter((segment) => segment.count > 0).map((segment) => {
                  const percent = (segment.count / totalOrders) * 100;
                  return (
                    <div
                      key={segment.label}
                      className={segment.className}
                      style={{ width: `${percent}%` }}
                      title={`${segment.label}: ${segment.count} of ${totalOrders} (${percent.toFixed(1)}%)`}
                    >
                      {percent >= 12 ? `${Math.round(percent)}%` : ''}
                    </div>
                  );
                })}
              </div>
              <p className="sd-cell-mute" style={{ marginTop: 12 }}>
                Distribution for {totalOrders} order{totalOrders === 1 ? '' : 's'} this month.
              </p>
            </>
          )}
          <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', marginTop: 16 }}>
            <div>
              <div className="sd-card-subtitle" style={{ fontSize: 13 }}>
                Quoted delivery fees this month
              </div>
              <Link
                to="/merchant/dashboard/billing"
                className="sd-text-link"
                style={{ display: 'inline-block', marginTop: 6 }}
              >
                Open billing statement
              </Link>
            </div>
            <span style={{ fontSize: 20, fontWeight: 600, color: 'var(--ink)' }}>
              {billing.isLoading
                ? 'Loading…'
                : billing.isError
                  ? 'Unavailable'
                  : billing.isSuccess
                    ? billing.data.currency
                      ? new Intl.NumberFormat(undefined, { style: 'currency', currency: billing.data.currency }).format(billing.data.quotedFeeTotal)
                      : `${new Intl.NumberFormat(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(billing.data.quotedFeeTotal)} (currency unavailable)`
                    : 'Unavailable'}
            </span>
          </div>
          <p className="sd-cell-mute" style={{ marginTop: 8 }}>
            {billing.isError
              ? 'Could not load the monthly billing summary.'
              : billing.isSuccess && billing.data.orderCount === 0
                ? 'No quoted delivery fees recorded this month.'
                : 'Quoted delivery fees from persisted orders this month.'}
          </p>
          {billing.isError && <Button type="button" variant="secondary" onClick={() => void billing.refetch()}>Retry billing summary</Button>}
        </section>

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
            {webhookDeliveries.isLoading && <p>Loading delivery history…</p>}
            {webhookDeliveries.isError && <p role="alert">Delivery history is unavailable.</p>}
            {webhookDeliveries.isSuccess && webhookDeliveries.data.items.length === 0 && <p>No webhook delivery attempts yet.</p>}
            {webhookDeliveries.data?.items.slice(0, 5).map(item => <div key={item.id} className="sd-webhook-event-row"><span>{item.eventType}</span><span>{item.status}</span><span>{item.attempts} attempts</span></div>)}
          </div>
        </section>
      </div>
    </main>
  );
}
