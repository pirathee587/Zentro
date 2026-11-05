import React, { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/shared/auth/useAuth';
import { friendlyApiError } from '@/shared/api/apiClient';
import {
  listCouriersAdmin,
  updateCourierAdmin,
  type ListCouriersParams,
  type UpdateCourierAdminRequest,
} from '../api/courierAdminApi';
import type { CourierProfile, VehicleType } from '@/portals/courier/api/courierApi';

const VEHICLE_OPTIONS: (VehicleType | '')[] = ['', 'BICYCLE', 'MOTORCYCLE', 'CAR', 'VAN', 'TRUCK'];

export function CouriersPage() {
  const { user } = useAuth();
  const queryClient = useQueryClient();

  const [search, setSearch] = useState('');
  const [activeFilter, setActiveFilter] = useState<'ALL' | 'ACTIVE' | 'INACTIVE'>('ALL');
  const [zoneFilter, setZoneFilter] = useState('');

  // Selected courier for modal editing
  const [selectedCourier, setSelectedCourier] = useState<CourierProfile | null>(null);

  // Modal form state
  const [editActive, setEditActive] = useState<boolean>(true);
  const [editZone, setEditZone] = useState<string>('');
  const [editVehicle, setEditVehicle] = useState<string>('');
  const [editName, setEditName] = useState<string>('');
  const [editPhone, setEditPhone] = useState<string>('');

  const [modalError, setModalError] = useState<string | null>(null);
  const [actionSuccess, setActionSuccess] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const queryParams: ListCouriersParams = {
    search: search.trim() || undefined,
    active: activeFilter === 'ALL' ? undefined : activeFilter === 'ACTIVE',
    zone: zoneFilter.trim() || undefined,
  };

  const couriersQuery = useQuery({
    queryKey: ['dispatcher', 'couriers', queryParams],
    enabled: Boolean(user?.accessToken),
    queryFn: () => listCouriersAdmin(queryParams, user?.accessToken),
    retry: false,
  });

  const updateMutation = useMutation({
    mutationFn: ({ id, data }: { id: string; data: UpdateCourierAdminRequest }) =>
      updateCourierAdmin(id, data, user?.accessToken),
    onSuccess: async () => {
      setActionSuccess('Courier profile successfully updated.');
      setActionError(null);
      setSelectedCourier(null);
      await queryClient.invalidateQueries({ queryKey: ['dispatcher', 'couriers'] });
    },
    onError: (err) => {
      setModalError(friendlyApiError(err, 'Failed to update courier profile. Please verify input.'));
    },
  });

  const openEditModal = (courier: CourierProfile) => {
    setSelectedCourier(courier);
    setEditActive(courier.active);
    setEditZone(courier.assignedZone || '');
    setEditVehicle(courier.vehicleType || '');
    setEditName(courier.name || '');
    setEditPhone(courier.phone || '');
    setModalError(null);
  };

  const handleModalSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedCourier) return;
    setModalError(null);

    const payload: UpdateCourierAdminRequest = {
      active: editActive,
      assignedZone: editZone.trim() || undefined,
      vehicleType: editVehicle || undefined,
      name: editName.trim() || undefined,
      phone: editPhone.trim() || undefined,
    };

    updateMutation.mutate({ id: selectedCourier.id, data: payload });
  };

  const formatDate = (isoStr?: string) => {
    if (!isoStr) return '—';
    const date = new Date(isoStr);
    return Number.isNaN(date.getTime())
      ? '—'
      : new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(date);
  };

  const couriers = couriersQuery.data || [];

  return (
    <main className="staff-main" aria-labelledby="courier-mgmt-title">
      <section className="staff-hero">
        <div>
          <span className="staff-eyebrow">FLEET & DISPATCH OPERATIONS</span>
          <h1 id="courier-mgmt-title">Courier Management</h1>
          <p>
            Monitor registered couriers, manage assignment zones, set vehicle categories, and maintain
            active dispatch eligibility.
          </p>
        </div>
        <span className="staff-hero-status">
          {couriersQuery.isPending ? 'Loading fleet…' : `${couriers.length} couriers registered`}
        </span>
      </section>

      {/* Success / Error Banners */}
      {actionSuccess && (
        <div className="courier-alert is-success" role="alert" style={{ marginTop: 24 }}>
          <span>{actionSuccess}</span>
          <button
            type="button"
            className="courier-dismiss-btn"
            onClick={() => setActionSuccess(null)}
          >
            Dismiss
          </button>
        </div>
      )}

      {actionError && (
        <div className="courier-alert is-error" role="alert" style={{ marginTop: 24 }}>
          <span>{actionError}</span>
          <button
            type="button"
            className="courier-dismiss-btn"
            onClick={() => setActionError(null)}
          >
            Dismiss
          </button>
        </div>
      )}

      {/* Filter and Search Bar */}
      <section className="staff-section" aria-label="Courier filters">
        <div className="staff-filter-bar">
          <div className="staff-filter-group">
            <input
              type="search"
              className="staff-filter-input"
              placeholder="Search by name, email, phone…"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              aria-label="Search couriers"
            />

            <select
              className="staff-filter-select"
              value={activeFilter}
              onChange={(e) => setActiveFilter(e.target.value as 'ALL' | 'ACTIVE' | 'INACTIVE')}
              aria-label="Filter by eligibility"
            >
              <option value="ALL">All Statuses</option>
              <option value="ACTIVE">Eligible (Active)</option>
              <option value="INACTIVE">Ineligible (Inactive)</option>
            </select>

            <input
              type="text"
              className="staff-filter-input"
              style={{ minWidth: 160 }}
              placeholder="Filter by zone…"
              value={zoneFilter}
              onChange={(e) => setZoneFilter(e.target.value)}
              aria-label="Filter by zone"
            />
          </div>

          <button
            type="button"
            className="staff-review-button"
            onClick={() => void couriersQuery.refetch()}
            disabled={couriersQuery.isFetching}
          >
            {couriersQuery.isFetching ? 'Refreshing…' : 'Refresh List'}
          </button>
        </div>
      </section>

      {/* Courier List */}
      <section className="staff-section" aria-label="Registered courier fleet">
        {couriersQuery.isPending ? (
          <div className="staff-panel staff-empty">Loading courier directory…</div>
        ) : couriersQuery.isError ? (
          <div className="staff-panel staff-error">
            {friendlyApiError(couriersQuery.error, 'Could not load courier list. Please try again.')}
          </div>
        ) : couriers.length === 0 ? (
          <div className="staff-panel staff-empty">
            No registered couriers found matching your filters.
          </div>
        ) : (
          <div className="staff-courier-list">
            {couriers.map((courier) => (
              <article className="staff-courier-card" key={courier.id}>
                <div className="staff-courier-header">
                  <div>
                    <h3>{courier.name || 'Unnamed Courier'}</h3>
                    <p>
                      {courier.email} • {courier.phone || 'No phone recorded'}
                    </p>
                  </div>
                  <div className="staff-courier-tags">
                    <span
                      className={`courier-badge ${courier.active ? 'is-active' : 'is-inactive'}`}
                      role="status"
                    >
                      {courier.active ? 'Eligible' : 'Ineligible'}
                    </span>
                    <span className="courier-badge is-status">Account: {courier.accountStatus}</span>
                  </div>
                </div>

                <div className="staff-courier-grid">
                  <div className="staff-courier-cell">
                    <span>Assigned Zone</span>
                    <strong>{courier.assignedZone || 'Unassigned'}</strong>
                  </div>
                  <div className="staff-courier-cell">
                    <span>Vehicle Type</span>
                    <strong>{courier.vehicleType || 'Not assigned'}</strong>
                  </div>
                  <div className="staff-courier-cell">
                    <span>Registered Since</span>
                    <strong>{formatDate(courier.createdAt)}</strong>
                  </div>
                  <div className="staff-courier-cell">
                    <span>Last Updated</span>
                    <strong>{formatDate(courier.updatedAt)}</strong>
                  </div>
                </div>

                <div className="staff-courier-actions">
                  <button
                    type="button"
                    className="staff-review-button is-approve"
                    onClick={() => openEditModal(courier)}
                    aria-label={`Edit profile for ${courier.name || courier.email}`}
                  >
                    Edit Operational Profile
                  </button>
                </div>
              </article>
            ))}
          </div>
        )}
      </section>

      {/* Edit Courier Modal Dialog */}
      {selectedCourier && (
        <div
          className="staff-modal-overlay"
          role="dialog"
          aria-modal="true"
          aria-labelledby="modal-title"
        >
          <div className="staff-modal">
            <div className="staff-modal-header">
              <div>
                <span className="staff-eyebrow">DISPATCH CONTROLS</span>
                <h2 id="modal-title">Edit Courier Profile</h2>
                <p>{selectedCourier.name} ({selectedCourier.email})</p>
              </div>
              <button
                type="button"
                className="staff-modal-close"
                onClick={() => setSelectedCourier(null)}
                aria-label="Close modal"
              >
                ×
              </button>
            </div>

            {modalError && (
              <div className="courier-alert is-error" role="alert">
                <span>{modalError}</span>
              </div>
            )}

            <form onSubmit={handleModalSubmit} className="staff-modal-body">
              <div className="staff-modal-field">
                <label className="staff-checkbox-label">
                  <input
                    type="checkbox"
                    checked={editActive}
                    onChange={(e) => setEditActive(e.target.checked)}
                  />
                  <span>Dispatch Eligibility (Active for parcel assignments)</span>
                </label>
              </div>

              <div className="staff-modal-field">
                <label htmlFor="edit-assigned-zone">Assigned Delivery Zone</label>
                <input
                  id="edit-assigned-zone"
                  type="text"
                  placeholder="e.g. Colombo Central, Kandy Hub"
                  value={editZone}
                  onChange={(e) => setEditZone(e.target.value)}
                  maxLength={64}
                />
              </div>

              <div className="staff-modal-field">
                <label htmlFor="edit-vehicle-type">Vehicle Category</label>
                <select
                  id="edit-vehicle-type"
                  value={editVehicle}
                  onChange={(e) => setEditVehicle(e.target.value)}
                >
                  <option value="">None / Not Assigned</option>
                  {VEHICLE_OPTIONS.filter(Boolean).map((v) => (
                    <option key={v} value={v}>
                      {v}
                    </option>
                  ))}
                </select>
              </div>

              <div className="staff-modal-field">
                <label htmlFor="edit-name">Display Name</label>
                <input
                  id="edit-name"
                  type="text"
                  value={editName}
                  onChange={(e) => setEditName(e.target.value)}
                  maxLength={200}
                />
              </div>

              <div className="staff-modal-field">
                <label htmlFor="edit-phone">Contact Phone</label>
                <input
                  id="edit-phone"
                  type="tel"
                  value={editPhone}
                  onChange={(e) => setEditPhone(e.target.value)}
                  maxLength={32}
                />
              </div>

              <div className="staff-modal-footer">
                <button
                  type="button"
                  className="staff-review-button"
                  onClick={() => setSelectedCourier(null)}
                  disabled={updateMutation.isPending}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="staff-review-button is-approve"
                  disabled={updateMutation.isPending}
                >
                  {updateMutation.isPending ? 'Saving…' : 'Save Changes'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </main>
  );
}
