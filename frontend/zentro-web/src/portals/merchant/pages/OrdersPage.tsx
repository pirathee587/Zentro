import React, { useState, useMemo } from 'react';

interface OrderItem {
  id: string;
  orderRef: string;
  trackingRef: string;
  destination: string;
  origin: string;
  weightCategory: string;
  weightKg: string;
  status: 'In transit' | 'Delivered' | 'Failed' | 'Created' | 'Picked up' | 'Out for delivery';
  fee: string;
  webhook: 'Delivered' | 'None' | 'Retrying' | 'Failed';
  timeline: {
    name: string;
    time: string;
    state: 'completed' | 'active' | 'pending';
  }[];
}

const ALL_ORDERS: OrderItem[] = [
  {
    id: 'o1',
    orderRef: 'LT-5521',
    trackingRef: 'ZT-7K2M9QX4PA',
    destination: 'Kandy',
    origin: 'Colombo 07',
    weightCategory: 'Medium',
    weightKg: '2 kg',
    status: 'In transit',
    fee: 'LKR 1,072',
    webhook: 'Delivered',
    timeline: [
      { name: 'Created', time: '27 Oct, 08:05', state: 'completed' },
      { name: 'Picked up', time: '27 Oct, 09:10', state: 'completed' },
      { name: 'In transit', time: '27 Oct, 11:42', state: 'active' },
      { name: 'Out for delivery', time: 'Pending', state: 'pending' },
      { name: 'Delivered', time: 'Pending', state: 'pending' },
    ],
  },
  {
    id: 'o2',
    orderRef: 'LT-5520',
    trackingRef: 'ZT-3HD8V1NC6R',
    destination: 'Galle',
    origin: 'Colombo 03',
    weightCategory: 'Small',
    weightKg: '1 kg',
    status: 'Delivered',
    fee: 'LKR 656',
    webhook: 'Delivered',
    timeline: [
      { name: 'Created', time: '26 Oct, 09:00', state: 'completed' },
      { name: 'Picked up', time: '26 Oct, 10:15', state: 'completed' },
      { name: 'In transit', time: '26 Oct, 12:30', state: 'completed' },
      { name: 'Out for delivery', time: '26 Oct, 14:10', state: 'completed' },
      { name: 'Delivered', time: '26 Oct, 16:45', state: 'completed' },
    ],
  },
  {
    id: 'o3',
    orderRef: 'LT-5519',
    trackingRef: 'ZT-9WQ4T7BE2X',
    destination: 'Colombo 05',
    origin: 'Colombo 02',
    weightCategory: 'Small',
    weightKg: '0.5 kg',
    status: 'In transit',
    fee: 'LKR 380',
    webhook: 'None',
    timeline: [
      { name: 'Created', time: '26 Oct, 11:10', state: 'completed' },
      { name: 'Picked up', time: '26 Oct, 12:00', state: 'completed' },
      { name: 'In transit', time: '26 Oct, 13:20', state: 'active' },
      { name: 'Out for delivery', time: 'Pending', state: 'pending' },
      { name: 'Delivered', time: 'Pending', state: 'pending' },
    ],
  },
  {
    id: 'o4',
    orderRef: 'LT-5518',
    trackingRef: 'ZT-5RN2K8YD1V',
    destination: 'Jaffna',
    origin: 'Colombo 07',
    weightCategory: 'Medium',
    weightKg: '3 kg',
    status: 'Failed',
    fee: 'LKR 1,168',
    webhook: 'Delivered',
    timeline: [
      { name: 'Created', time: '25 Oct, 08:30', state: 'completed' },
      { name: 'Picked up', time: '25 Oct, 09:45', state: 'completed' },
      { name: 'In transit', time: '25 Oct, 12:00', state: 'completed' },
      { name: 'Failed', time: '25 Oct, 16:00', state: 'completed' },
    ],
  },
  {
    id: 'o5',
    orderRef: 'LT-5517',
    trackingRef: 'ZT-2XB6M4QH8C',
    destination: 'Matara',
    origin: 'Colombo 05',
    weightCategory: 'Large',
    weightKg: '6 kg',
    status: 'Delivered',
    fee: 'LKR 2,016',
    webhook: 'None',
    timeline: [
      { name: 'Created', time: '24 Oct, 08:00', state: 'completed' },
      { name: 'Picked up', time: '24 Oct, 09:00', state: 'completed' },
      { name: 'In transit', time: '24 Oct, 11:00', state: 'completed' },
      { name: 'Delivered', time: '24 Oct, 15:30', state: 'completed' },
    ],
  },
  {
    id: 'o6',
    orderRef: 'LT-5516',
    trackingRef: 'ZT-8FT1P5ZK3J',
    destination: 'Negombo',
    origin: 'Colombo 01',
    weightCategory: 'Small',
    weightKg: '1 kg',
    status: 'Out for delivery',
    fee: 'LKR 656',
    webhook: 'Retrying',
    timeline: [
      { name: 'Created', time: '24 Oct, 10:10', state: 'completed' },
      { name: 'Picked up', time: '24 Oct, 11:20', state: 'completed' },
      { name: 'In transit', time: '24 Oct, 12:45', state: 'completed' },
      { name: 'Out for delivery', time: '24 Oct, 14:00', state: 'active' },
      { name: 'Delivered', time: 'Pending', state: 'pending' },
    ],
  },
  {
    id: 'o7',
    orderRef: 'LT-5515',
    trackingRef: 'ZT-4CJ9W2LR7N',
    destination: 'Kurunegala',
    origin: 'Colombo 03',
    weightCategory: 'Medium',
    weightKg: '2 kg',
    status: 'Picked up',
    fee: 'LKR 1,072',
    webhook: 'Failed',
    timeline: [
      { name: 'Created', time: '23 Oct, 09:15', state: 'completed' },
      { name: 'Picked up', time: '23 Oct, 10:30', state: 'active' },
      { name: 'In transit', time: 'Pending', state: 'pending' },
      { name: 'Delivered', time: 'Pending', state: 'pending' },
    ],
  },
  {
    id: 'o8',
    orderRef: 'LT-5514',
    trackingRef: 'ZT-6MV3D8HA9E',
    destination: 'Colombo 03',
    origin: 'Colombo 07',
    weightCategory: 'Small',
    weightKg: '0.5 kg',
    status: 'Created',
    fee: 'LKR 380',
    webhook: 'Delivered',
    timeline: [
      { name: 'Created', time: '23 Oct, 14:00', state: 'active' },
      { name: 'Picked up', time: 'Pending', state: 'pending' },
      { name: 'Delivered', time: 'Pending', state: 'pending' },
    ],
  },
  {
    id: 'o9',
    orderRef: 'LT-5513',
    trackingRef: 'ZT-10K7S4TB5G',
    destination: 'Kandy',
    origin: 'Colombo 04',
    weightCategory: 'Small',
    weightKg: '1 kg',
    status: 'Delivered',
    fee: 'LKR 656',
    webhook: 'None',
    timeline: [
      { name: 'Created', time: '21 Oct, 08:30', state: 'completed' },
      { name: 'Picked up', time: '21 Oct, 09:40', state: 'completed' },
      { name: 'In transit', time: '21 Oct, 11:20', state: 'completed' },
      { name: 'Delivered', time: '21 Oct, 14:15', state: 'completed' },
    ],
  },
  {
    id: 'o10',
    orderRef: 'LT-5512',
    trackingRef: 'ZT-9DP5X1NW2Y',
    destination: 'Galle',
    origin: 'Colombo 02',
    weightCategory: 'Medium',
    weightKg: '4 kg',
    status: 'Delivered',
    fee: 'LKR 1,264',
    webhook: 'None',
    timeline: [
      { name: 'Created', time: '19 Oct, 09:00', state: 'completed' },
      { name: 'Picked up', time: '19 Oct, 10:10', state: 'completed' },
      { name: 'In transit', time: '19 Oct, 12:00', state: 'completed' },
      { name: 'Delivered', time: '19 Oct, 15:40', state: 'completed' },
    ],
  },
  {
    id: 'o11',
    orderRef: 'LT-5511',
    trackingRef: 'ZT-3ZG8R6CE4U',
    destination: 'Badulla',
    origin: 'Colombo 01',
    weightCategory: 'Large',
    weightKg: '8 kg',
    status: 'Delivered',
    fee: 'LKR 2,448',
    webhook: 'None',
    timeline: [
      { name: 'Created', time: '15 Oct, 07:45', state: 'completed' },
      { name: 'Picked up', time: '15 Oct, 08:50', state: 'completed' },
      { name: 'In transit', time: '15 Oct, 11:10', state: 'completed' },
      { name: 'Delivered', time: '15 Oct, 17:20', state: 'completed' },
    ],
  },
  {
    id: 'o12',
    orderRef: 'LT-5510',
    trackingRef: 'ZT-7HN2L9VM6F',
    destination: 'Colombo 07',
    origin: 'Colombo 03',
    weightCategory: 'Small',
    weightKg: '0.5 kg',
    status: 'Delivered',
    fee: 'LKR 380',
    webhook: 'None',
    timeline: [
      { name: 'Created', time: '12 Oct, 10:20', state: 'completed' },
      { name: 'Picked up', time: '12 Oct, 11:15', state: 'completed' },
      { name: 'In transit', time: '12 Oct, 12:10', state: 'completed' },
      { name: 'Delivered', time: '12 Oct, 13:45', state: 'completed' },
    ],
  },
];

export function OrdersPage() {
  const [filter, setFilter] = useState<'All' | 'Created' | 'In progress' | 'Delivered' | 'Failed'>('All');
  const [search, setSearch] = useState('');
  const [selectedOrderId, setSelectedOrderId] = useState('o1');

  const filteredOrders = useMemo(() => {
    return ALL_ORDERS.filter((order) => {
      // Filter tab
      if (filter === 'Created' && order.status !== 'Created') return false;
      if (filter === 'In progress' && !['In transit', 'Picked up', 'Out for delivery'].includes(order.status)) return false;
      if (filter === 'Delivered' && order.status !== 'Delivered') return false;
      if (filter === 'Failed' && order.status !== 'Failed') return false;

      // Search term
      if (search.trim()) {
        const q = search.toLowerCase();
        const matchesRef = order.orderRef.toLowerCase().includes(q);
        const matchesTracking = order.trackingRef.toLowerCase().includes(q);
        const matchesDest = order.destination.toLowerCase().includes(q);
        return matchesRef || matchesTracking || matchesDest;
      }

      return true;
    });
  }, [filter, search]);

  const selectedOrder = useMemo(() => {
    return ALL_ORDERS.find((o) => o.id === selectedOrderId) || ALL_ORDERS[0];
  }, [selectedOrderId]);

  return (
    <main className="sd-page-container" aria-label="Merchant Orders">
      {/* Page Header */}
      <div className="sd-page-header">
        <div className="sd-page-title-group">
          <h1 className="sd-page-title">Orders</h1>
          <p className="sd-page-subtitle">
            Orders created through the API. Each order is linked to one parcel and its tracking reference.
          </p>
        </div>
      </div>

      {/* 2-Column Grid */}
      <div className="sd-grid-2col" style={{ gap: 24, alignItems: 'start' }}>
        {/* Left Column: Filter, Search, Orders Table */}
        <section className="sd-card" aria-labelledby="orders-list-heading">
          <h2 id="orders-list-heading" className="sr-only">Orders List</h2>

          {/* Filter Pills */}
          <div className="sd-filter-pills" role="tablist" aria-label="Filter orders by status" style={{ marginBottom: 20 }}>
            {(['All', 'Created', 'In progress', 'Delivered', 'Failed'] as const).map((tab) => (
              <button
                key={tab}
                type="button"
                role="tab"
                aria-selected={filter === tab}
                className={`sd-filter-pill ${filter === tab ? 'is-active' : ''}`}
                onClick={() => setFilter(tab)}
              >
                {tab}
              </button>
            ))}
          </div>

          {/* Search Box */}
          <div className="sd-search-input-wrap">
            <input
              type="text"
              className="sd-input"
              placeholder="Search by order reference, tracking reference or place"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              aria-label="Search orders"
            />
          </div>

          {/* Table */}
          <div className="sd-table-wrap">
            <table className="sd-table" aria-label="Orders table">
              <thead>
                <tr>
                  <th scope="col" style={{ width: '20%' }}>Order ref</th>
                  <th scope="col" style={{ width: '24%' }}>Destination</th>
                  <th scope="col" style={{ width: '24%' }}>Status</th>
                  <th scope="col" style={{ width: '18%' }}>Fee</th>
                  <th scope="col" style={{ width: '14%' }}>Webhook</th>
                </tr>
              </thead>
              <tbody>
                {filteredOrders.map((order) => {
                  const isSelected = order.id === selectedOrderId;
                  return (
                    <tr
                      key={order.id}
                      style={{ cursor: 'pointer' }}
                      onClick={() => setSelectedOrderId(order.id)}
                    >
                      <td className="sd-cell-bold">
                        <span className="sd-cell-code">{order.orderRef}</span>
                      </td>
                      <td>{order.destination}</td>
                      <td>
                        {order.status === 'In transit' && <span className="sd-badge-yellow">In transit</span>}
                        {order.status === 'Delivered' && <span className="sd-badge-dark">Delivered</span>}
                        {order.status === 'Failed' && <span className="sd-badge-coral">Failed</span>}
                        {order.status === 'Created' && <span className="sd-badge-light">Created</span>}
                        {order.status === 'Picked up' && <span className="sd-badge-yellow">Picked up</span>}
                        {order.status === 'Out for delivery' && <span className="sd-badge-yellow">Out for delivery</span>}
                      </td>
                      <td className="sd-cell-mute">{order.fee}</td>
                      <td>
                        {order.webhook === 'Delivered' && <span className="sd-badge-dark">Delivered</span>}
                        {order.webhook === 'Retrying' && <span className="sd-badge-yellow">Retrying</span>}
                        {order.webhook === 'Failed' && <span className="sd-badge-coral">Failed</span>}
                        {order.webhook === 'None' && <span className="sd-cell-mute">None</span>}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </section>

        {/* Right Column: Order Detail & API Info */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
          {/* Order Detail Card */}
          <section className="sd-card" aria-labelledby="order-detail-heading">
            <div className="sd-card-header" style={{ marginBottom: 12 }}>
              <h2 id="order-detail-heading" className="sd-card-title">Order detail</h2>
            </div>

            <div style={{ fontSize: 24, fontWeight: 700, color: 'var(--ink)', letterSpacing: '-0.3px' }}>
              {selectedOrder.orderRef}
            </div>
            <div className="sd-card-subtitle" style={{ fontSize: 13, marginTop: 4 }}>
              Tracking {selectedOrder.trackingRef}
            </div>

            <div style={{ display: 'flex', alignItems: 'center', gap: 10, margin: '14px 0 20px', flexWrap: 'wrap' }}>
              {selectedOrder.status === 'In transit' && <span className="sd-badge-yellow">In transit</span>}
              {selectedOrder.status === 'Delivered' && <span className="sd-badge-dark">Delivered</span>}
              {selectedOrder.status === 'Failed' && <span className="sd-badge-coral">Failed</span>}
              {selectedOrder.status === 'Created' && <span className="sd-badge-light">Created</span>}
              {selectedOrder.status === 'Picked up' && <span className="sd-badge-yellow">Picked up</span>}
              {selectedOrder.status === 'Out for delivery' && <span className="sd-badge-yellow">Out for delivery</span>}

              <span className="sd-cell-mute" style={{ fontSize: 13 }}>
                {selectedOrder.origin} to {selectedOrder.destination} · {selectedOrder.weightCategory} · {selectedOrder.weightKg} · {selectedOrder.fee}
              </span>
            </div>

            {/* Stepper Timeline */}
            <div className="sd-order-timeline">
              {selectedOrder.timeline.map((step) => (
                <div key={step.name} className="sd-order-timeline-step">
                  <div className="sd-order-timeline-rail">
                    <span
                      className={`sd-order-timeline-dot ${
                        step.state === 'completed'
                          ? 'sd-order-dot-dark'
                          : step.state === 'active'
                          ? 'sd-order-dot-yellow'
                          : 'sd-order-dot-open'
                      }`}
                    />
                    <span className="sd-order-timeline-line" />
                  </div>
                  <div className="sd-order-timeline-content">
                    <span className="sd-order-step-name">{step.name}</span>
                    <span className="sd-order-step-time">{step.time}</span>
                  </div>
                </div>
              ))}
            </div>

            {/* Webhook deliveries sub-section */}
            <div className="sd-border-top-subtle" style={{ paddingTop: 16 }}>
              <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--ink)', marginBottom: 8 }}>
                Webhook deliveries
              </div>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', fontSize: 13 }}>
                <span className="sd-cell-mute">Status updated: {selectedOrder.status}</span>
                <span className="sd-badge-dark">Delivered</span>
              </div>
            </div>
          </section>

          {/* Bottom Card: Create orders via the API */}
          <section className="sd-card" aria-labelledby="api-orders-heading">
            <h2 id="api-orders-heading" className="sd-card-title">Create orders via the API</h2>
            <p className="sd-card-subtitle" style={{ fontSize: 13, lineHeight: 1.6, marginTop: 8 }}>
              Orders are submitted by your system. Re-sending the same order reference returns the original order instead of creating a duplicate.
            </p>

            <div className="sd-code-box">
              POST /api/v1/merchant/orders<br />
              Authorization: Bearer &lt;access token&gt;
            </div>

            <span className="sd-cell-mute" style={{ fontSize: 12 }}>
              Example only. See the API reference for the exact schema.
            </span>
          </section>
        </div>
      </div>
    </main>
  );
}
