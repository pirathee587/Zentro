import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { AlertCircle, Info, CheckCircle2, Zap } from 'lucide-react';

interface UnassignedParcel {
  reference: string;
  route: string;
  weightCategory: string;
  weightKg: string;
  selectedCourier: string;
}

interface CourierWorkloadItem {
  name: string;
  activeCount: number;
  percentage: number;
}

interface MerchantAppItem {
  businessName: string;
  appliedDate: string;
}

const INITIAL_UNASSIGNED: UnassignedParcel[] = [
  {
    reference: 'ZT-7K2M9QX4PA',
    route: 'Colombo 03 to Kandy',
    weightCategory: 'Medium',
    weightKg: '2 kg',
    selectedCourier: 'Saman Perera',
  },
  {
    reference: 'ZT-3HD8V1NC6R',
    route: 'Galle to Matara',
    weightCategory: 'Small',
    weightKg: '0.8 kg',
    selectedCourier: 'Saman Perera',
  },
  {
    reference: 'ZT-9WQ4T7BE2X',
    route: 'Negombo to Colombo 07',
    weightCategory: 'Large',
    weightKg: '5 kg',
    selectedCourier: 'Saman Perera',
  },
  {
    reference: 'ZT-5RN2K8YD1V',
    route: 'Kandy to Nuwara Eliya',
    weightCategory: 'Small',
    weightKg: '1.5 kg',
    selectedCourier: 'Saman Perera',
  },
  {
    reference: 'ZT-2XB6M4QH8C',
    route: 'Colombo 02 to Gampaha',
    weightCategory: 'Medium',
    weightKg: '3 kg',
    selectedCourier: 'Saman Perera',
  },
];

const COURIER_OPTIONS = ['Saman Perera', 'Kumari Silva', 'Ruwan Jayasinghe', 'Dilani Fernando', 'Nuwan Perera'];

const COURIER_WORKLOADS: CourierWorkloadItem[] = [
  { name: 'Saman Perera', activeCount: 3, percentage: 60 },
  { name: 'Kumari Silva', activeCount: 2, percentage: 40 },
  { name: 'Ruwan Jayasinghe', activeCount: 1, percentage: 20 },
  { name: 'Dilani Fernando', activeCount: 1, percentage: 20 },
];

const INITIAL_MERCHANTS: MerchantAppItem[] = [
  { businessName: 'Ceylon Crafts', appliedDate: '25 Oct 2026' },
  { businessName: 'Island Fresh', appliedDate: '26 Oct 2026' },
];

export function DispatchControlPage() {
  const [unassignedList, setUnassignedList] = useState<UnassignedParcel[]>(INITIAL_UNASSIGNED);
  const [assignedNotice, setAssignedNotice] = useState<string | null>(null);

  const handleCourierSelect = (index: number, courier: string) => {
    setUnassignedList((prev) => {
      const next = [...prev];
      next[index] = { ...next[index], selectedCourier: courier };
      return next;
    });
  };

  const handleAssign = (parcel: UnassignedParcel, index: number) => {
    setAssignedNotice(`Assigned ${parcel.reference} to ${parcel.selectedCourier}`);
    setTimeout(() => setAssignedNotice(null), 4000);
    // Optionally remove from unassigned list or mark assigned
    setUnassignedList((prev) => prev.filter((_, i) => i !== index));
  };

  return (
    <main className="sd-page-container">
      {/* Page Title & Counters */}
      <section className="sd-page-header">
        <div className="sd-page-title-group">
          <h1 className="sd-page-title">Dispatch control</h1>
          <p className="sd-page-subtitle">Island-wide · live</p>
        </div>

        <div className="sd-header-stats-group">
          <div className="sd-header-stat">
            <span className="sd-stat-number">{unassignedList.length}</span>
            <span className="sd-stat-label">Unassigned</span>
          </div>
          <div className="sd-header-stat">
            <span className="sd-stat-number">7</span>
            <span className="sd-stat-label">Active</span>
          </div>
          <div className="sd-header-stat">
            <span className="sd-stat-number">4</span>
            <span className="sd-stat-label">Couriers on duty</span>
          </div>
        </div>
      </section>

      {assignedNotice && (
        <div className="sd-flash-notice" role="status">
          <CheckCircle2 size={16} />
          <span>{assignedNotice}</span>
        </div>
      )}

      {/* Main 2-Column Grid */}
      <div className="sd-grid-2col">
        {/* Top-Left: Unassigned Parcels */}
        <div className="sd-card">
          <div className="sd-card-header">
            <h2 className="sd-card-title">Unassigned parcels</h2>
            <Link to="/dispatcher/parcels" className="sd-text-link">
              All parcels
            </Link>
          </div>

          <div className="sd-unassigned-list">
            {unassignedList.length === 0 ? (
              <p className="sd-muted-empty">All parcels currently assigned.</p>
            ) : (
              unassignedList.map((parcel, idx) => (
                <div key={parcel.reference} className="sd-unassigned-row">
                  <div className="sd-unassigned-info">
                    <span className="sd-parcel-ref">{parcel.reference}</span>
                    <span className="sd-parcel-meta">
                      {parcel.route} · {parcel.weightCategory} · {parcel.weightKg}
                    </span>
                  </div>

                  <div className="sd-unassigned-actions">
                    <select
                      className="sd-select"
                      value={parcel.selectedCourier}
                      onChange={(e) => handleCourierSelect(idx, e.target.value)}
                      aria-label={`Select courier for ${parcel.reference}`}
                    >
                      {COURIER_OPTIONS.map((c) => (
                        <option key={c} value={c}>
                          {c}
                        </option>
                      ))}
                    </select>

                    <button
                      type="button"
                      className="sd-btn-yellow"
                      onClick={() => handleAssign(parcel, idx)}
                    >
                      Assign
                    </button>
                  </div>
                </div>
              ))
            )}
          </div>
        </div>

        {/* Top-Right: Courier Workload */}
        <div className="sd-card">
          <div className="sd-card-header">
            <h2 className="sd-card-title">Courier workload</h2>
            <Link to="/dispatcher/couriers" className="sd-text-link">
              Manage
            </Link>
          </div>

          <div className="sd-workload-list">
            {COURIER_WORKLOADS.map((c) => (
              <div key={c.name} className="sd-workload-item">
                <div className="sd-workload-meta">
                  <span className="sd-workload-name">{c.name}</span>
                  <span className="sd-workload-count">{c.activeCount} active</span>
                </div>
                <div className="sd-progress-track">
                  <div
                    className="sd-progress-fill"
                    style={{ width: `${c.percentage}%` }}
                    aria-valuenow={c.percentage}
                    aria-valuemin={0}
                    aria-valuemax={100}
                  />
                </div>
              </div>
            ))}
          </div>

          <p className="sd-card-footnote">Active = assigned and not yet delivered or failed.</p>
        </div>

        {/* Bottom-Left: Merchant Applications */}
        <div className="sd-card">
          <div className="sd-card-header">
            <h2 className="sd-card-title">Merchant applications</h2>
          </div>

          <div className="sd-merchant-list">
            {INITIAL_MERCHANTS.map((m) => (
              <div key={m.businessName} className="sd-merchant-row">
                <div className="sd-merchant-info">
                  <span className="sd-merchant-name">{m.businessName}</span>
                  <span className="sd-merchant-date">Applied {m.appliedDate}</span>
                </div>
                <Link
                  to="/dispatcher/merchant-approvals"
                  className="sd-btn-pill-light"
                >
                  Review
                </Link>
              </div>
            ))}
          </div>
        </div>

        {/* Bottom-Right: Alerts & Activity (Dark Card) */}
        <div className="sd-card sd-dark-card">
          <div className="sd-card-header">
            <h2 className="sd-card-title sd-dark-title">Alerts and activity</h2>
          </div>

          <div className="sd-alerts-list">
            {/* Alert 1 */}
            <div className="sd-alert-item">
              <div className="sd-alert-icon-wrap sd-icon-warning">
                <AlertCircle size={18} />
              </div>
              <div className="sd-alert-content">
                <span className="sd-alert-title">2 notification attempts failed in the last hour</span>
                <Link to="/dispatcher/integration-attempts" className="sd-alert-sub sd-alert-link">
                  Check the notification log
                </Link>
              </div>
            </div>

            {/* Alert 2 */}
            <div className="sd-alert-item">
              <div className="sd-alert-icon-wrap sd-icon-muted">
                <Info size={18} />
              </div>
              <div className="sd-alert-content">
                <span className="sd-alert-title">1 webhook delivery exhausted its retries</span>
                <span className="sd-alert-sub">Merchant endpoint unreachable</span>
              </div>
            </div>

            {/* Activity 3 */}
            <div className="sd-alert-item">
              <div className="sd-alert-icon-wrap sd-icon-muted">
                <CheckCircle2 size={18} />
              </div>
              <div className="sd-alert-content">
                <span className="sd-alert-title">ZT-2YB4F8KQ1D delivered by Kumari Silva</span>
                <span className="sd-alert-sub">10:12</span>
              </div>
            </div>

            {/* Activity 4 */}
            <div className="sd-alert-item">
              <div className="sd-alert-icon-wrap sd-icon-muted">
                <Zap size={18} />
              </div>
              <div className="sd-alert-content">
                <span className="sd-alert-title">ZT-3ZG8R6CE4U out for delivery</span>
                <span className="sd-alert-sub">09:58</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </main>
  );
}

