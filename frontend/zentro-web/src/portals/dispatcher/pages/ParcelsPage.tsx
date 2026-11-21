import React, { useState, useMemo } from 'react';
import { Search } from 'lucide-react';
import './ParcelsPage.css';

export type ParcelStatus =
  | 'Unassigned'
  | 'Assigned'
  | 'Picked up'
  | 'In transit'
  | 'Out for delivery'
  | 'Delivered'
  | 'Failed';

export interface ParcelItem {
  reference: string;
  route: string;
  status: ParcelStatus;
  courier: string;
  weightCategory?: string;
  weightKg?: string;
}

/** Default courier options available in the preview assignment dropdown. */
const COURIER_OPTIONS: string[] = [
  'Saman Perera',
  'Kumari Silva',
  'Ruwan Jayasinghe',
  'Dilani Fernando',
  'Nuwan Perera',
];

type FilterTab = 'All' | 'Unassigned' | 'Active' | 'Delivered' | 'Failed';

export interface ParcelsPageProps {
  /** Seed parcel list — used only by unit tests to inject fixtures. Live UI shows no data. */
  initialParcels?: ParcelItem[];
}

export function ParcelsPage({ initialParcels }: ParcelsPageProps = {}) {
  const seed = initialParcels ?? [];
  const [parcels, setParcels] = useState<ParcelItem[]>(seed);
  const [activeTab, setActiveTab] = useState<FilterTab>('All');
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedParcel, setSelectedParcel] = useState<ParcelItem | null>(seed[0] ?? null);
  const [assignCourier, setAssignCourier] = useState(COURIER_OPTIONS[0] ?? '');
  const [notice, setNotice] = useState<string | null>(null);

  const filteredParcels = useMemo(() => {
    return parcels.filter((p) => {
      // Tab filter
      if (activeTab === 'Unassigned' && p.status !== 'Unassigned') return false;
      if (
        activeTab === 'Active' &&
        !['Assigned', 'Picked up', 'In transit', 'Out for delivery'].includes(p.status)
      )
        return false;
      if (activeTab === 'Delivered' && p.status !== 'Delivered') return false;
      if (activeTab === 'Failed' && p.status !== 'Failed') return false;

      // Search query
      if (searchQuery.trim()) {
        const q = searchQuery.toLowerCase();
        return (
          p.reference.toLowerCase().includes(q) ||
          p.route.toLowerCase().includes(q) ||
          p.courier.toLowerCase().includes(q)
        );
      }
      return true;
    });
  }, [parcels, activeTab, searchQuery]);

  const handleAssign = () => {
    if (!selectedParcel) return;
    setParcels((prev) =>
      prev.map((item) =>
        item.reference === selectedParcel.reference
          ? { ...item, status: 'Assigned', courier: assignCourier }
          : item,
      ),
    );
    setSelectedParcel((prev) =>
      prev ? { ...prev, status: 'Assigned', courier: assignCourier } : prev,
    );    setNotice(`Preview updated: ${selectedParcel.reference} → ${assignCourier}. No changes saved.`);
    setTimeout(() => setNotice(null), 4000);
  };

  const getStatusBadge = (status: ParcelStatus) => {
    switch (status) {
      case 'Unassigned':
        return <span className="sd-status-text">Unassigned</span>;
      case 'Assigned':
      case 'Picked up':
      case 'In transit':
      case 'Out for delivery':
        return <span className="sd-badge-yellow">{status}</span>;
      case 'Delivered':
        return <span className="sd-badge-dark">{status}</span>;
      case 'Failed':
        return <span className="sd-badge-danger">{status}</span>;
      default:
        return <span>{status}</span>;
    }
  };

  // Timeline milestone active state calculator
  const isMilestoneReached = (milestone: string, status: ParcelStatus) => {
    const sequence = ['Created', 'Picked up', 'In transit', 'Out for delivery', 'Delivered'];
    const currentIdx =
      status === 'Unassigned' || status === 'Assigned'
        ? 0
        : status === 'Picked up'
          ? 1
          : status === 'In transit'
            ? 2
            : status === 'Out for delivery'
              ? 3
              : status === 'Delivered'
                ? 4
                : 0;
    const targetIdx = sequence.indexOf(milestone);
    return targetIdx <= currentIdx;
  };

  return (
    <main className="sd-page-container sd-parcels-page">
      {/* Page Title & Counters */}
      <section className="sd-page-header">
        <div className="sd-page-title-group">
          <h1 className="sd-page-title">Parcels</h1>
          <p className="sd-page-subtitle">
            {parcels.length} parcels ·{' '}
            {parcels.filter((parcel) => parcel.status === 'Unassigned').length} unassigned
          </p>
        </div>
      </section>

      <aside className="sd-parcels-preview-note" aria-label="Preview notice">
        <strong>Preview workspace</strong>
        <p>
          These are the existing sample parcels. Courier changes update this preview only and are
          not saved to the backend.
        </p>
        <a href="/dispatcher/queue">Open the live Dispatch Queue for assignments →</a>
      </aside>
      {notice && (
        <div className="sd-flash-notice" role="status">
          <span>{notice}</span>
        </div>
      )}

      {/* 2-Column Layout */}
      <div className="sd-parcels-layout">
        {/* Left Column: Filter Pills + Search + Parcels Table */}
        <div className="sd-card sd-parcels-card">
          {/* Filter Pills */}
          <div className="sd-filter-pills-row" role="tablist" aria-label="Parcel status filters">
            {(['All', 'Unassigned', 'Active', 'Delivered', 'Failed'] as FilterTab[]).map((tab) => (
              <button
                key={tab}
                type="button"
                role="tab"
                aria-selected={activeTab === tab}
                className={`sd-filter-pill ${activeTab === tab ? 'is-active' : ''}`}
                onClick={() => setActiveTab(tab)}
              >
                {tab}
              </button>
            ))}
          </div>

          {/* Search Bar */}
          <div className="sd-search-box">
            <Search size={16} className="sd-search-icon" />
            <input
              type="text"
              placeholder="Search by reference or place"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="sd-search-input"
              aria-label="Search by reference or place"
            />
          </div>

          {/* Parcels Table */}
          <div className="sd-table-wrap" role="region" aria-label="Parcels table" tabIndex={0}>
            <table className="sd-parcels-table">
              <caption className="sr-only">
                Parcels matching the selected status and search. Select a reference to view details.
              </caption>
              <thead>
                <tr>
                  <th scope="col">Reference</th>
                  <th scope="col">Route</th>
                  <th scope="col">Status</th>
                  <th scope="col">Courier</th>
                </tr>
              </thead>
              <tbody>
                {!filteredParcels.length && (
                  <tr>
                    <td colSpan={4} className="sd-table-empty">
                      No parcels match your filters and search.
                    </td>
                  </tr>
                )}
                {filteredParcels.map((parcel) => {
                  const isSelected = selectedParcel?.reference === parcel.reference;
                  return (
                    <tr
                      key={parcel.reference}
                      onClick={() => setSelectedParcel(parcel)}
                      className={`sd-clickable-row ${isSelected ? 'is-selected' : ''}`}
                    >
                      <td className="sd-cell-ref">
                        <button
                          type="button"
                          className="sd-ref-link"
                          aria-pressed={isSelected}
                          aria-controls="parcel-detail-panel"
                          onClick={(e) => {
                            e.stopPropagation();
                            setSelectedParcel(parcel);
                          }}
                        >
                          {parcel.reference}
                        </button>
                      </td>
                      <td className="sd-cell-route">{parcel.route}</td>
                      <td className="sd-cell-status">{getStatusBadge(parcel.status)}</td>
                      <td className="sd-cell-courier">{parcel.courier}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>

        {/* Right Column: Parcel Detail Card */}
        <section
          className="sd-card sd-detail-card"
          id="parcel-detail-panel"
          aria-labelledby="parcel-detail-title"
          aria-label="Parcel detail"
        >
          {selectedParcel ? (
            <>
              <div className="sd-detail-header">
                <h2 className="sd-detail-title" id="parcel-detail-title">
                  Parcel detail
                </h2>
                <div className="sd-detail-ref">{selectedParcel.reference}</div>
                <div className="sd-detail-tags">
                  <span className="sd-tag-status">{selectedParcel.status}</span>
                </div>
              </div>

              <dl className="sd-detail-facts">
                <div className="sd-detail-route">
                  <dt>Route</dt>
                  <dd>{selectedParcel.route}</dd>
                </div>
                <div>
                  <dt>Size</dt>
                  <dd>{selectedParcel.weightCategory || 'Unavailable'}</dd>
                </div>
                <div>
                  <dt>Weight</dt>
                  <dd>{selectedParcel.weightKg || 'Unavailable'}</dd>
                </div>
              </dl>
              {/* Courier row */}
              <div className="sd-detail-courier-row">
                <span className="sd-courier-label">Courier</span>
                <span className="sd-courier-val">
                  {selectedParcel.courier === 'None' ? 'Unassigned' : selectedParcel.courier}
                </span>
              </div>

              {/* Stepper Timeline */}
              <h3 className="sd-timeline-heading">Delivery progress</h3>
              <div className="sd-timeline-stepper" role="list" aria-label="Delivery progress">
                {['Created', 'Picked up', 'In transit', 'Out for delivery', 'Delivered'].map(
                  (step, i, arr) => {
                    const reached = isMilestoneReached(step, selectedParcel.status);
                    return (
                      <div
                        key={step}
                        className="sd-stepper-node"
                        role="listitem"
                        aria-current={
                          step === selectedParcel.status ||
                          (step === 'Created' &&
                            ['Unassigned', 'Assigned'].includes(selectedParcel.status))
                            ? 'step'
                            : undefined
                        }
                      >
                        <div className="sd-node-bullet-col" aria-hidden="true">
                          <div className={`sd-bullet ${reached ? 'is-reached' : ''}`} />
                          {i < arr.length - 1 && (
                            <div className={`sd-bullet-line ${reached ? 'is-reached' : ''}`} />
                          )}
                        </div>
                        <div className="sd-node-text-col">
                          <span className={`sd-step-name ${reached ? 'is-bold' : ''}`}>{step}</span>
                          {step === selectedParcel.status && (
                            <span className="sd-current-step">Current</span>
                          )}
                        </div>
                      </div>
                    );
                  },
                )}
              </div>

              <p className="sd-timeline-note">
                Progress reflects the selected status. Event timestamps are unavailable.
              </p>
              {/* Assign To Section */}
              <div className="sd-detail-assign-section">
                <label htmlFor="assign-courier-select" className="sd-assign-label">
                  Preview courier
                </label>
                <div className="sd-assign-actions">
                  <select
                    id="assign-courier-select"
                    className="sd-select"
                    value={assignCourier}
                    onChange={(e) => setAssignCourier(e.target.value)}
                  >
                    {COURIER_OPTIONS.map((c) => (
                      <option key={c} value={c}>
                        {c}
                      </option>
                    ))}
                  </select>

                  <button type="button" className="sd-btn-yellow" onClick={handleAssign}>
                    Update preview
                  </button>
                </div>
              </div>
            </>
          ) : (
            <div className="sd-detail-empty">
              <h2 className="sd-detail-title" id="parcel-detail-title">
                Parcel detail
              </h2>
              <p className="sd-detail-empty-msg">Select a parcel to view its details.</p>
            </div>
          )}
        </section>
      </div>
    </main>
  );
}
