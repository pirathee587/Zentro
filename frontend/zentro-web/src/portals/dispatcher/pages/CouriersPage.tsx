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
import { UsersPage } from './UsersPage';
import { MerchantApprovalsPage } from './MerchantApprovalsPage';
import { AddCourierModal } from '../components/AddCourierModal';
import { UsersRound } from 'lucide-react';
import { getCourierWorkload, workloadQueryKey } from '../api/workloadApi';
import './PeoplePage.css';

const VEHICLE_OPTIONS: (VehicleType | '')[] = ['', 'BICYCLE', 'MOTORCYCLE', 'CAR', 'VAN', 'TRUCK'];

export function CouriersPage() {
  const { user } = useAuth();
  const queryClient = useQueryClient();

  const [activeTab, setActiveTab] = useState<'Couriers' | 'Users' | 'Merchants'>('Couriers');
  const [isAddCourierOpen, setIsAddCourierOpen] = useState(false);

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
    queryFn: ({ signal }) => listCouriersAdmin(queryParams, user?.accessToken, signal),
  });

  const updateMutation = useMutation({
    mutationFn: ({ id, data }: { id: string; data: UpdateCourierAdminRequest }) =>
      updateCourierAdmin(id, data, user?.accessToken),
    onSuccess: async () => {
      setActionSuccess('Courier profile successfully updated.');
      setActionError(null);
      setSelectedCourier(null);
      await queryClient.invalidateQueries({ queryKey: ['dispatcher', 'couriers'] });
      await queryClient.invalidateQueries({ queryKey: ['dispatcher', 'workload'] });
    },
    onError: (err) => {
      const message = friendlyApiError(
        err,
        'Failed to update courier profile. Please verify input.',
      );
      if (selectedCourier) setModalError(message);
      else setActionError(message);
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

  const workload = useQuery({
    queryKey: workloadQueryKey({}, user?.id),
    enabled: Boolean(user?.accessToken) && activeTab === 'Couriers',
    queryFn: ({ signal }) => getCourierWorkload({}, user?.accessToken, signal),
  });
  const workloadCounts = new Map(
    workload.data?.items.map((item) => [item.courierId, item.assignmentCount]),
  );
  const apiCouriers = couriersQuery.data;
  const hasApiData = Array.isArray(apiCouriers) && apiCouriers.length > 0;

  return (
    <main className="sd-page-container people-page" aria-labelledby="courier-mgmt-title">
      {/* Title */}
      <section className="sd-page-header">
        <div className="sd-page-title-group">
          <h1
            className="sd-page-title"
            id="courier-mgmt-title"
            aria-label="User Management — Couriers and people"
          >
            User Management
          </h1>
          <p className="sd-page-subtitle">
            Manage couriers, user accounts and merchant applications.
          </p>
        </div>
      </section>

      {/* Sub-tabs: Couriers | Users | Merchants */}
      <div className="sd-subtabs-row" role="tablist" aria-label="People management tabs">
        {(['Couriers', 'Users', 'Merchants'] as const).map((tab) => (
          <button
            key={tab}
            id={`people-tab-${tab}`}
            type="button"
            role="tab"
            aria-selected={activeTab === tab}
            aria-controls="people-tab-panel"
            tabIndex={activeTab === tab ? 0 : -1}
            className={`sd-filter-pill ${activeTab === tab ? 'is-active' : ''}`}
            onClick={() => setActiveTab(tab)}
            onKeyDown={(event) => {
              const tabs = ['Couriers', 'Users', 'Merchants'] as const;
              const index = tabs.indexOf(tab);
              const next =
                event.key === 'ArrowRight'
                  ? (index + 1) % 3
                  : event.key === 'ArrowLeft'
                    ? (index + 2) % 3
                    : event.key === 'Home'
                      ? 0
                      : event.key === 'End'
                        ? 2
                        : undefined;
              if (next === undefined) return;
              event.preventDefault();
              setActiveTab(tabs[next]);
              document.getElementById(`people-tab-${tabs[next]}`)?.focus();
            }}
          >
            {tab}
          </button>
        ))}
      </div>
      <div id="people-tab-panel" role="tabpanel" aria-labelledby={`people-tab-${activeTab}`}>
        {' '}
        {/* Tab: Users */}
        {activeTab === 'Users' && (
          <div className="people-tab-content">
            <UsersPage embedded />
          </div>
        )}
        {/* Tab: Merchants */}
        {activeTab === 'Merchants' && (
          <div className="people-tab-content">
            <MerchantApprovalsPage embedded />
          </div>
        )}
        {/* Tab: Couriers */}
        {activeTab === 'Couriers' && (
          <>
            {/* Courier Header Actions */}
            <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 16, marginBottom: 8 }}>
              <button
                type="button"
                className="sd-btn-yellow"
                style={{ fontWeight: 600, padding: '9px 18px', display: 'inline-flex', alignItems: 'center', gap: '6px' }}
                onClick={() => setIsAddCourierOpen(true)}
              >
                + Add Courier
              </button>
            </div>

            {/* Success / Error Banners */}
            {actionSuccess && (
              <div className="sd-flash-notice" role="status" style={{ marginTop: 20 }}>
                <span>{actionSuccess}</span>
              </div>
            )}

            {actionError && (
              <div className="sd-flash-error" role="alert" style={{ marginTop: 20 }}>
                <span>{actionError}</span>
              </div>
            )}

            {/* Existing server-backed search and filters */}
            <div className="people-courier-filters" role="search" aria-label="Courier filters">
              <input
                type="search"
                placeholder="Search by name, email, phone…"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                aria-label="Search couriers"
              />
              <select
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
                placeholder="Filter by zone…"
                value={zoneFilter}
                onChange={(e) => setZoneFilter(e.target.value)}
                aria-label="Filter by zone"
              />
            </div>

            {couriersQuery.isPending && (
              <div className="staff-panel staff-empty">Loading courier directory…</div>
            )}

            {/* If the test or live API loaded data, show the API couriers with exact selectors */}
            {hasApiData ? (
              <div className="sd-card people-directory">
                <div
                  className="sd-table-wrap"
                  role="region"
                  aria-label="Courier directory"
                  tabIndex={0}
                >
                  <table className="sd-parcels-table">
                    <caption className="sr-only">
                      Registered couriers and recorded assignment counts
                    </caption>
                    <thead>
                      <tr>
                        <th>Courier</th>
                        <th>Zone · vehicle</th>
                        <th>Recorded assignments</th>
                        <th>Status</th>
                        <th>Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      {apiCouriers.map((courier) => (
                        <tr key={courier.id}>
                          <td>
                            <div>
                              <strong>{courier.name}</strong>
                            </div>
                            <div className="sd-cell-sub">
                              {courier.email} • {courier.phone || 'No phone recorded'}
                            </div>
                          </td>
                          <td>
                            <div>{courier.assignedZone || 'Unassigned'}</div>
                            <div className="sd-cell-sub">
                              {courier.vehicleType || 'Not assigned'}
                            </div>
                          </td>
                          <td>
                            <span className="people-workload-count">
                              {workload.isPending
                                ? 'Loading…'
                                : workload.isError
                                  ? 'Unavailable'
                                  : (workloadCounts.get(courier.id) ?? 'Unavailable')}
                            </span>
                          </td>
                          <td>
                            <span className={`sd-badge-${courier.active ? 'dark' : 'outline'}`}>
                              {courier.active ? 'Active' : 'Inactive'}
                            </span>
                          </td>
                          <td>
                            <button
                              type="button"
                              className="sd-action-link"
                              onClick={() => openEditModal(courier)}
                              aria-label={`Edit profile for ${courier.name || courier.email}`}
                            >
                              Edit profile
                            </button>
                            <button
                              type="button"
                              className="staff-review-button"
                              disabled={updateMutation.isPending}
                              aria-label={`${courier.active ? 'Deactivate' : 'Activate'} ${courier.name || courier.email}`}
                              onClick={() => {
                                setActionSuccess(null);
                                setActionError(null);
                                updateMutation.mutate({
                                  id: courier.id,
                                  data: { active: !courier.active },
                                });
                              }}
                            >
                              {courier.active ? 'Deactivate' : 'Activate'}
                            </button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <p className="people-table-note">
                  Workload shows all recorded assignments; active-only counts and capacity are
                  unavailable.
                </p>
              </div>
            ) : (
              !couriersQuery.isPending && (
                <div
                  className="sd-card people-empty"
                  role={couriersQuery.isError ? 'alert' : 'status'}
                >
                  <UsersRound size={28} aria-hidden="true" />
                  <strong>
                    {couriersQuery.isError
                      ? 'Courier directory unavailable'
                      : 'No couriers match these filters'}
                  </strong>
                  <p>
                    {couriersQuery.isError
                      ? friendlyApiError(
                          couriersQuery.error,
                          'Please try loading the directory again.',
                        )
                      : 'Try a different name, eligibility status or delivery zone.'}
                  </p>
                  {couriersQuery.isError && (
                    <button
                      type="button"
                      className="staff-review-button"
                      onClick={() => void couriersQuery.refetch()}
                    >
                      Retry
                    </button>
                  )}
                </div>
              )
            )}
            {/* Edit Modal (used by CouriersPage.test.tsx) */}
            {selectedCourier && (
              <div
                className="staff-modal-overlay"
                role="dialog"
                aria-modal="true"
                aria-labelledby="modal-title"
              >
                <div className="staff-modal-card">
                  <div className="staff-modal-header">
                    <h2 id="modal-title">Edit Courier Profile</h2>
                    <button
                      type="button"
                      className="staff-modal-close"
                      onClick={() => setSelectedCourier(null)}
                      aria-label="Close dialog"
                    >
                      ×
                    </button>
                  </div>

                  <form onSubmit={handleModalSubmit} className="staff-modal-form">
                    {modalError && (
                      <div className="courier-alert is-error" role="alert">
                        {modalError}
                      </div>
                    )}

                    <div className="staff-form-group">
                      <label htmlFor="edit-name">Courier Name</label>
                      <input
                        id="edit-name"
                        type="text"
                        className="staff-modal-input"
                        value={editName}
                        onChange={(e) => setEditName(e.target.value)}
                      />
                    </div>

                    <div className="staff-form-group">
                      <label htmlFor="edit-phone">Phone Number</label>
                      <input
                        id="edit-phone"
                        type="text"
                        className="staff-modal-input"
                        value={editPhone}
                        onChange={(e) => setEditPhone(e.target.value)}
                      />
                    </div>

                    <div className="staff-form-group">
                      <label htmlFor="edit-zone">Assigned Delivery Zone</label>
                      <input
                        id="edit-zone"
                        type="text"
                        className="staff-modal-input"
                        value={editZone}
                        onChange={(e) => setEditZone(e.target.value)}
                        placeholder="e.g. Colombo Metro, Kandy Hub"
                      />
                    </div>

                    <div className="staff-form-group">
                      <label htmlFor="edit-vehicle">Vehicle Category</label>
                      <select
                        id="edit-vehicle"
                        className="staff-modal-select"
                        value={editVehicle}
                        onChange={(e) => setEditVehicle(e.target.value)}
                      >
                        {VEHICLE_OPTIONS.map((v) => (
                          <option key={v} value={v}>
                            {v || 'None / Not Assigned'}
                          </option>
                        ))}
                      </select>
                    </div>

                    <div className="staff-form-group staff-checkbox-group">
                      <label className="staff-checkbox-label">
                        <input
                          type="checkbox"
                          checked={editActive}
                          onChange={(e) => setEditActive(e.target.checked)}
                        />
                        <span>Active (Eligible for automated and manual dispatch)</span>
                      </label>
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

            {/* Add Courier Modal */}
            {isAddCourierOpen && (
              <AddCourierModal
                onClose={() => setIsAddCourierOpen(false)}
                onCreated={async (courierEmail) => {
                  setIsAddCourierOpen(false);
                  setActionSuccess(
                    `Courier created! Temporary credentials sent to ${courierEmail}. Keycloak will require them to set their password on first sign-in.`,
                  );
                  setActionError(null);
                  await queryClient.invalidateQueries({ queryKey: ['dispatcher', 'couriers'] });
                  await queryClient.invalidateQueries({ queryKey: ['dispatcher', 'workload'] });
                }}
              />
            )}
          </>
        )}
      </div>
    </main>
  );
}
