import React, { useMemo, useState } from 'react';
import { ArrowRight, Box, MapPin, Package, Phone, User } from 'lucide-react';
import { Button, Input } from '@/shared/ui';
import { friendlyApiError } from '@/shared/api/apiClient';
import { useMerchantOrdersQuery } from '../hooks/useMerchantQueries';

const labels: Record<string, string> = {
  CREATED: 'Created',
  PICKED_UP: 'Picked up',
  IN_TRANSIT: 'In transit',
  OUT_FOR_DELIVERY: 'Out for delivery',
  DELIVERED: 'Delivered',
  FAILED: 'Failed',
};

function statusBadgeClass(status: string): string {
  switch (status) {
    case 'DELIVERED':
      return 'sd-badge-dark';
    case 'FAILED':
      return 'sd-badge-danger';
    case 'OUT_FOR_DELIVERY':
    case 'IN_TRANSIT':
    case 'PICKED_UP':
      return 'sd-badge-yellow';
    default:
      return 'sd-tag-status';
  }
}

export function OrdersPage() {
  const query = useMerchantOrdersQuery();
  const [filter, setFilter] = useState('All');
  const [search, setSearch] = useState('');
  const [selected, setSelected] = useState('');

  const orders = query.data ?? [];
  const shown = useMemo(
    () =>
      orders.filter((o) => {
        const status = labels[o.status] ?? o.status;
        const matches =
          filter === 'All' ||
          (filter === 'In progress'
            ? ['PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY'].includes(o.status)
            : status === filter);
        const q = search.trim().toLowerCase();
        return (
          matches &&
          (!q ||
            `${o.externalOrderRef} ${o.trackingRef} ${o.recipient.city ?? ''} ${o.recipient.name}`
              .toLowerCase()
              .includes(q))
        );
      }),
    [orders, filter, search],
  );

  const active = orders.find((o) => o.id === selected) ?? shown[0];

  return (
    <main className="sd-page-container" aria-label="Merchant Orders">
      <div className="sd-page-header">
        <div className="sd-page-title-group">
          <h1 className="sd-page-title">Orders</h1>
          <p className="sd-page-subtitle">Track and inspect merchant orders and delivery statuses.</p>
        </div>
      </div>

      <section className="sd-card">
        {query.isLoading && <p className="sd-cell-mute">Loading orders…</p>}
        {query.isError && (
          <div role="alert" style={{ padding: '16px 0' }}>
            <p className="form-error">{friendlyApiError(query.error, 'Could not load orders.')}</p>
            <Button
              type="button"
              variant="secondary"
              onClick={() => void query.refetch()}
              style={{ marginTop: 8 }}
            >
              Retry
            </Button>
          </div>
        )}

        {query.isSuccess && (
          <>
            <div className="sd-filter-pills" role="tablist" aria-label="Filter orders">
              {['All', 'Created', 'In progress', 'Delivered', 'Failed'].map((f) => (
                <Button
                  key={f}
                  type="button"
                  role="tab"
                  aria-selected={filter === f}
                  className={`sd-filter-pill ${filter === f ? 'is-active' : ''}`}
                  onClick={() => setFilter(f)}
                >
                  {f}
                </Button>
              ))}
            </div>

            <div className="sd-search-input-wrap" style={{ margin: '16px 0' }}>
              <Input
                className="sd-input"
                aria-label="Search orders"
                placeholder="Search order, tracking reference or destination…"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
              />
            </div>

            {shown.length === 0 ? (
              <p className="sd-cell-mute" style={{ padding: '32px 0', textAlign: 'center' }}>
                {orders.length === 0 ? 'No orders yet.' : 'No orders match your search.'}
              </p>
            ) : (
              <div className="sd-table-wrap">
                <table className="sd-table">
                  <thead>
                    <tr>
                      <th>Order Ref</th>
                      <th>Destination</th>
                      <th>Status</th>
                      <th>Quoted Fee</th>
                      <th>Created</th>
                    </tr>
                  </thead>
                  <tbody>
                    {shown.map((o) => (
                      <tr
                        key={o.id}
                        onClick={() => setSelected(o.id)}
                        aria-selected={active?.id === o.id}
                        style={{
                          cursor: 'pointer',
                          background: active?.id === o.id ? 'var(--parcel-selected)' : undefined,
                        }}
                      >
                        <td className="sd-cell-bold">
                          <strong>{o.externalOrderRef}</strong>
                        </td>
                        <td>{o.recipient.city ?? o.recipient.address}</td>
                        <td>
                          <span className={statusBadgeClass(o.status)}>
                            {labels[o.status] ?? o.status}
                          </span>
                        </td>
                        <td style={{ fontWeight: 600 }}>
                          {new Intl.NumberFormat(undefined, {
                            style: 'currency',
                            currency: o.currency,
                          }).format(o.fee)}
                        </td>
                        <td style={{ color: 'var(--mute)', fontSize: 12 }}>
                          {new Date(o.createdAt).toLocaleDateString([], {
                            month: 'short',
                            day: 'numeric',
                            hour: '2-digit',
                            minute: '2-digit',
                          })}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            {orders.length >= 50 && (
              <p className="sd-cell-mute" style={{ marginTop: 12, fontSize: 12 }}>
                Showing up to 50 recent orders. This list is not a complete history.
              </p>
            )}
          </>
        )}
      </section>

      {active && (
        <section
          className="sd-card"
          style={{
            marginTop: 24,
            padding: '24px 28px',
            border: '1px solid var(--line)',
            borderRadius: 16,
          }}
        >
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              flexWrap: 'wrap',
              gap: 12,
              paddingBottom: 16,
              borderBottom: '1px solid var(--line)',
            }}
          >
            <div>
              <span className="sd-eyebrow" style={{ fontSize: 11, letterSpacing: '0.08em' }}>
                ORDER · {active.externalOrderRef}
              </span>
              <h2 className="sd-card-title" style={{ margin: '4px 0 0', fontSize: 20 }}>
                Order detail
              </h2>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <span
                style={{
                  background: 'var(--gold-soft)',
                  padding: '4px 10px',
                  borderRadius: 6,
                  fontWeight: 700,
                  fontSize: 13,
                  letterSpacing: '0.5px',
                }}
              >
                {active.trackingRef}
              </span>
              <span className={statusBadgeClass(active.status)}>
                {labels[active.status] ?? active.status}
              </span>
            </div>
          </div>

          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))',
              gap: 20,
              marginTop: 20,
            }}
          >
            {/* Route */}
            <div
              style={{
                padding: 16,
                background: 'var(--paper)',
                borderRadius: 12,
                border: '1px solid var(--line)',
              }}
            >
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 6,
                  color: 'var(--mute)',
                  fontSize: 12,
                  fontWeight: 600,
                  marginBottom: 8,
                }}
              >
                <MapPin size={14} /> ROUTE
              </div>
              <div style={{ fontSize: 13, fontWeight: 600, display: 'flex', alignItems: 'center', gap: 8 }}>
                <span>{active.sender.city ?? active.sender.address}</span>
                <ArrowRight size={14} style={{ color: 'var(--mute)' }} />
                <span>{active.recipient.city ?? active.recipient.address}</span>
              </div>
            </div>

            {/* Recipient */}
            <div
              style={{
                padding: 16,
                background: 'var(--paper)',
                borderRadius: 12,
                border: '1px solid var(--line)',
              }}
            >
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 6,
                  color: 'var(--mute)',
                  fontSize: 12,
                  fontWeight: 600,
                  marginBottom: 8,
                }}
              >
                <User size={14} /> RECIPIENT
              </div>
              <div style={{ fontSize: 14, fontWeight: 700 }}>{active.recipient.name}</div>
              <div
                style={{
                  fontSize: 12,
                  color: 'var(--mute)',
                  display: 'flex',
                  alignItems: 'center',
                  gap: 4,
                  marginTop: 2,
                }}
              >
                <Phone size={12} /> {active.recipient.phone}
              </div>
            </div>

            {/* Parcel Details */}
            <div
              style={{
                padding: 16,
                background: 'var(--paper)',
                borderRadius: 12,
                border: '1px solid var(--line)',
              }}
            >
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 6,
                  color: 'var(--mute)',
                  fontSize: 12,
                  fontWeight: 600,
                  marginBottom: 8,
                }}
              >
                <Package size={14} /> PARCEL & FEE
              </div>
              <div style={{ fontSize: 13, fontWeight: 600 }}>
                {active.parcel.weightKg} kg · {active.parcel.category}
              </div>
              <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--ink)', marginTop: 4 }}>
                Fee:{' '}
                {new Intl.NumberFormat(undefined, {
                  style: 'currency',
                  currency: active.currency,
                }).format(active.fee)}
              </div>
            </div>
          </div>
        </section>
      )}
    </main>
  );
}
