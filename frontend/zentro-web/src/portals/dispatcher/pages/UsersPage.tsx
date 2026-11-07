import { useState, useId, useRef, useEffect, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/shared/auth/useAuth';
import { ApiClientError, friendlyApiError } from '@/shared/api/apiClient';
import {
  listUsers,
  updateUser,
  type AdminUserProfile,
  type AdminUpdateUserRequest,
} from '../api/adminApi';

// ─── Query key ─────────────────────────────────────────────────────────────────
const usersKey = (status: string, search: string) =>
  ['dispatcher', 'admin-users', status, search] as const;

// ─── Validation Helpers ────────────────────────────────────────────────────────
/**
 * Sri Lankan phone rule matching S1/S2: +94 or 0 followed by 9 digits.
 * Spaces and dashes are permitted formatting characters.
 */
// eslint-disable-next-line react-refresh/only-export-components
export function isValidSriLankanPhone(phone: string): boolean {
  const cleaned = phone.replace(/[\s-]/g, '');
  return /^(?:\+94|0)\d{9}$/.test(cleaned);
}

// ─── Status badge ───────────────────────────────────────────────────────────────
function StatusBadge({ status }: { status: string }) {
  const map: Record<string, string> = {
    ACTIVE: 'staff-badge-active',
    DISABLED: 'staff-badge-disabled',
    SUSPENDED: 'staff-badge-suspended',
  };
  return (
    <span className={`staff-badge ${map[status] ?? 'staff-badge-unknown'}`}>{status}</span>
  );
}

// ─── Role pill ──────────────────────────────────────────────────────────────────
function RolePill({ role }: { role: string }) {
  return <span className="staff-role-pill">{role}</span>;
}

// ─── Merchant status badge ──────────────────────────────────────────────────────
function MerchantStatusBadge({ status }: { status: string | null }) {
  if (!status) return null;
  const map: Record<string, string> = {
    APPROVED: 'staff-badge-active',
    PENDING: 'staff-badge-pending',
    REJECTED: 'staff-badge-disabled',
  };
  return (
    <span className={`staff-badge ${map[status] ?? 'staff-badge-unknown'}`}>
      Merchant: {status}
    </span>
  );
}

// ─── Status Change Confirmation Dialog ─────────────────────────────────────────
interface StatusChangeConfirmDialogProps {
  user: AdminUserProfile;
  newStatus: AdminUpdateUserRequest['accountStatus'];
  reason: string;
  onConfirm: () => void;
  onCancel: () => void;
  isPending: boolean;
}

function StatusChangeConfirmDialog({
  user,
  newStatus,
  reason,
  onConfirm,
  onCancel,
  isPending,
}: StatusChangeConfirmDialogProps) {
  const modalRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    modalRef.current?.focus();
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape' && !isPending) {
        onCancel();
      }
    }
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isPending, onCancel]);

  const isDeactivation = newStatus === 'DISABLED' || newStatus === 'SUSPENDED';

  return (
    <div
      className="staff-modal-overlay"
      role="dialog"
      aria-modal="true"
      aria-labelledby="confirm-status-title"
    >
      <div className="staff-modal" ref={modalRef} tabIndex={-1}>
        <div className="staff-modal-header">
          <div>
            <span className="staff-eyebrow">SECURITY CONFIRMATION</span>
            <h2 id="confirm-status-title">Confirm Account Status Change</h2>
            <p>
              Target user: <strong>{user.name ?? user.email}</strong> ({user.email})
            </p>
          </div>
          <button
            type="button"
            className="staff-modal-close"
            onClick={onCancel}
            disabled={isPending}
            aria-label="Close confirmation"
          >
            ✕
          </button>
        </div>

        <div className="staff-modal-body">
          <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
            <span>Status Transition:</span>
            <StatusBadge status={user.accountStatus} />
            <span aria-hidden="true">→</span>
            <StatusBadge status={newStatus ?? 'ACTIVE'} />
          </div>

          <p style={{ margin: '8px 0', fontSize: '13px', lineHeight: 1.6 }}>
            {isDeactivation ? (
              <span style={{ color: 'var(--err)', fontWeight: 600 }}>
                Warning: Changing account status to {newStatus} will revoke the user&apos;s active
                operational access across Zentro services.
              </span>
            ) : (
              <span>
                Activating this account will enable standard platform operations for the user.
              </span>
            )}
          </p>

          <div className="staff-field">
            <label>Audit Rationale</label>
            <div
              style={{
                padding: '10px 12px',
                background: 'var(--cream)',
                borderRadius: '8px',
                fontSize: '13px',
                color: reason ? 'var(--espresso)' : 'var(--muted)',
                fontStyle: reason ? 'normal' : 'italic',
                wordBreak: 'break-word',
              }}
            >
              {reason || 'No specific rationale provided.'}
            </div>
          </div>

          <small className="staff-field-hint">
            This operational change and its rationale will be permanently recorded in the immutable
            audit trail (identity.user_admin_audit).
          </small>
        </div>

        <div className="staff-modal-footer">
          <button
            type="button"
            className="staff-review-button"
            onClick={onCancel}
            disabled={isPending}
          >
            Cancel
          </button>
          <button
            type="button"
            className="staff-review-button is-approve"
            disabled={isPending}
            onClick={onConfirm}
          >
            {isPending ? 'Applying change…' : 'Confirm & apply'}
          </button>
        </div>
      </div>
    </div>
  );
}

// ─── User edit panel ────────────────────────────────────────────────────────────
interface UserEditPanelProps {
  user: AdminUserProfile;
  currentUserId: string;
  onClose: () => void;
  onSaved: (user: AdminUserProfile, statusChanged: boolean) => void;
}

function UserEditPanel({
  user,
  currentUserId,
  onClose,
  onSaved,
}: UserEditPanelProps) {
  const nameId = useId();
  const phoneId = useId();
  const statusId = useId();
  const reasonId = useId();

  const queryClient = useQueryClient();
  const [name, setName] = useState(user.name ?? '');
  const [phone, setPhone] = useState(user.phone ?? '');
  const [accountStatus, setAccountStatus] = useState<AdminUpdateUserRequest['accountStatus']>(
    user.accountStatus,
  );
  const [reason, setReason] = useState('');
  const [clientErrors, setClientErrors] = useState<Record<string, string>>({});
  const [serverFieldErrors, setServerFieldErrors] = useState<Record<string, string>>({});
  const [saveError, setSaveError] = useState('');
  const [showConfirmModal, setShowConfirmModal] = useState(false);

  const isSelf = user.id === currentUserId;
  const statusChanged = accountStatus !== user.accountStatus;

  const save = useMutation({
    mutationFn: (requestPayload: AdminUpdateUserRequest) => {
      return updateUser(user.id, requestPayload);
    },
    onSuccess: async (updated) => {
      setSaveError('');
      setServerFieldErrors({});
      setShowConfirmModal(false);
      await queryClient.invalidateQueries({ queryKey: ['dispatcher', 'admin-users'] });
      onSaved(updated, statusChanged);
    },
    onError: (error: unknown) => {
      setShowConfirmModal(false);
      if (error instanceof ApiClientError) {
        if (error.fieldErrors && Object.keys(error.fieldErrors).length > 0) {
          setServerFieldErrors(error.fieldErrors);
        }
        if (error.response?.data && typeof error.response.data === 'object') {
          const code = (error.response.data as Record<string, unknown>).code;
          if (code === 'SELF_STATUS_CHANGE') {
            setSaveError('You cannot change your own account status.');
            return;
          }
          if (code === 'PROTECTED_FIELD') {
            setSaveError(
              error.problem.detail ?? 'Protected identity or role fields cannot be updated.'
            );
            return;
          }
        }
        setSaveError(
          error.problem.detail ??
            friendlyApiError(error, 'Could not update the user. Please check your inputs.')
        );
      } else {
        setSaveError(friendlyApiError(error, 'Could not update the user. Please try again.'));
      }
    },
  });

  function buildPayload(): AdminUpdateUserRequest {
    const request: AdminUpdateUserRequest = {};
    if (name.trim()) request.name = name.trim();
    if (phone.trim()) request.phone = phone.trim();
    if (reason.trim()) request.reason = reason.trim();
    // Self-lockout guard: if self, never submit a modified status
    if (!isSelf || accountStatus === 'ACTIVE') {
      request.accountStatus = accountStatus;
    }
    return request;
  }

  function validateClient(): boolean {
    const errors: Record<string, string> = {};
    if (name.trim().length === 0) {
      errors.name = 'Name must not be empty (1-200 characters).';
    } else if (name.trim().length > 200) {
      errors.name = 'Name must not exceed 200 characters.';
    }

    if (phone.trim().length > 0) {
      if (!isValidSriLankanPhone(phone.trim())) {
        errors.phone = 'Enter a valid Sri Lankan phone number (e.g. +94 77 123 4567 or 0771234567).';
      }
    }

    if (reason.length > 500) {
      errors.reason = 'Reason must not exceed 500 characters.';
    }

    setClientErrors(errors);
    return Object.keys(errors).length === 0;
  }

  function handleSaveClick() {
    setSaveError('');
    setServerFieldErrors({});
    if (!validateClient()) {
      return;
    }

    // Sensitive status change triggers confirmation dialog
    if (statusChanged && !isSelf) {
      setShowConfirmModal(true);
    } else {
      save.mutate(buildPayload());
    }
  }

  function handleConfirmDialogSubmit() {
    save.mutate(buildPayload());
  }

  return (
    <>
      <div
        role="dialog"
        aria-modal="true"
        aria-label={`Edit ${user.name ?? user.email}`}
        className="staff-edit-panel"
      >
        <div className="staff-edit-panel-header">
          <div>
            <span className="staff-eyebrow">EDIT USER</span>
            <h2>{user.name ?? user.email}</h2>
            <small>{user.email}</small>
          </div>
          <button
            type="button"
            aria-label="Close"
            className="staff-close-btn"
            onClick={onClose}
          >
            ✕
          </button>
        </div>

        <div className="staff-edit-panel-body">
          {/* User Details & Identity metadata */}
          <div
            style={{
              padding: '12px 14px',
              background: 'var(--cream)',
              borderRadius: '10px',
              display: 'grid',
              gap: '6px',
              fontSize: '12px',
            }}
          >
            <div>
              <span className="staff-muted">User ID: </span>
              <code style={{ fontSize: '11px' }}>{user.id}</code>
            </div>
            <div>
              <span className="staff-muted">Email: </span>
              <span>{user.email}</span>
            </div>
            {user.merchantStatus && (
              <div>
                <span className="staff-muted">Merchant Status: </span>
                <MerchantStatusBadge status={user.merchantStatus} />
              </div>
            )}
          </div>

          {/* Display Name */}
          <div className="staff-field">
            <label htmlFor={nameId}>Display name</label>
            <input
              id={nameId}
              type="text"
              value={name}
              maxLength={200}
              placeholder={user.email}
              aria-invalid={Boolean(clientErrors.name || serverFieldErrors.name)}
              onChange={(e) => {
                setName(e.target.value);
                if (clientErrors.name) {
                  setClientErrors((prev) => {
                    const next = { ...prev };
                    delete next.name;
                    return next;
                  });
                }
                if (serverFieldErrors.name) {
                  setServerFieldErrors((prev) => {
                    const next = { ...prev };
                    delete next.name;
                    return next;
                  });
                }
              }}
            />
            {(clientErrors.name || serverFieldErrors.name) && (
              <span className="staff-field-error" role="alert">
                {clientErrors.name || serverFieldErrors.name}
              </span>
            )}
          </div>

          {/* Contact Phone */}
          <div className="staff-field">
            <label htmlFor={phoneId}>Phone</label>
            <input
              id={phoneId}
              type="tel"
              value={phone}
              maxLength={32}
              placeholder="+94 77 123 4567"
              aria-invalid={Boolean(clientErrors.phone || serverFieldErrors.phone)}
              onChange={(e) => {
                setPhone(e.target.value);
                if (clientErrors.phone) {
                  setClientErrors((prev) => {
                    const next = { ...prev };
                    delete next.phone;
                    return next;
                  });
                }
                if (serverFieldErrors.phone) {
                  setServerFieldErrors((prev) => {
                    const next = { ...prev };
                    delete next.phone;
                    return next;
                  });
                }
              }}
            />
            <small className="staff-field-hint">
              Sri Lankan phone format (+94 or 0 followed by 9 digits)
            </small>
            {(clientErrors.phone || serverFieldErrors.phone) && (
              <span className="staff-field-error" role="alert">
                {clientErrors.phone || serverFieldErrors.phone}
              </span>
            )}
          </div>

          {/* Account status */}
          <div className="staff-field">
            <label htmlFor={statusId}>Account status</label>
            <select
              id={statusId}
              value={accountStatus}
              disabled={isSelf}
              onChange={(e) => {
                setAccountStatus(e.target.value as AdminUpdateUserRequest['accountStatus']);
                if (serverFieldErrors.accountStatus) {
                  setServerFieldErrors((prev) => {
                    const next = { ...prev };
                    delete next.accountStatus;
                    return next;
                  });
                }
              }}
            >
              <option value="ACTIVE">Active</option>
              <option value="DISABLED">Disabled</option>
              <option value="SUSPENDED">Suspended</option>
            </select>
            {isSelf && (
              <small className="staff-field-hint">
                You cannot change your own account status.
              </small>
            )}
            {serverFieldErrors.accountStatus && (
              <span className="staff-field-error" role="alert">
                {serverFieldErrors.accountStatus}
              </span>
            )}
          </div>

          {/* Reason for change (Audit rationale) */}
          <div className="staff-field">
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <label htmlFor={reasonId}>Reason for change</label>
              <span className="staff-field-hint" style={{ fontSize: '11px' }}>
                {reason.length}/500
              </span>
            </div>
            <textarea
              id={reasonId}
              rows={3}
              maxLength={500}
              placeholder="Provide a rationale for this administrative modification (logged in audit trail)…"
              value={reason}
              aria-invalid={Boolean(clientErrors.reason || serverFieldErrors.reason)}
              onChange={(e) => {
                setReason(e.target.value);
                if (clientErrors.reason) {
                  setClientErrors((prev) => {
                    const next = { ...prev };
                    delete next.reason;
                    return next;
                  });
                }
                if (serverFieldErrors.reason) {
                  setServerFieldErrors((prev) => {
                    const next = { ...prev };
                    delete next.reason;
                    return next;
                  });
                }
              }}
              style={{
                padding: '8px 12px',
                border: '1px solid var(--line)',
                borderRadius: '8px',
                background: 'var(--cream)',
                color: 'var(--espresso)',
                font: 'inherit',
                fontSize: '13px',
                resize: 'vertical',
              }}
            />
            <small className="staff-field-hint">
              Recorded in the append-only audit trail (identity.user_admin_audit).
            </small>
            {(clientErrors.reason || serverFieldErrors.reason) && (
              <span className="staff-field-error" role="alert">
                {clientErrors.reason || serverFieldErrors.reason}
              </span>
            )}
          </div>

          {/* Role & Permission Governance (Strictly Read-Only via Keycloak) */}
          <div className="staff-field-row">
            <strong>Roles (Governed by Keycloak)</strong>
            <div className="staff-role-list">
              {user.roles.length > 0 ? (
                user.roles.map((r) => <RolePill key={r} role={r} />)
              ) : (
                <span className="staff-muted">No roles assigned</span>
              )}
            </div>
            <small className="staff-field-hint">
              Role assignment is managed centrally in Keycloak. Role modifications are prohibited
              through this API to protect identity governance.
            </small>
          </div>

          {saveError && (
            <p role="alert" className="staff-error" style={{ marginTop: '12px' }}>
              {saveError}
            </p>
          )}
        </div>

        <div className="staff-edit-panel-footer">
          <button type="button" className="staff-review-button" onClick={onClose}>
            Cancel
          </button>
          <button
            type="button"
            className="staff-review-button is-approve"
            disabled={save.isPending}
            onClick={handleSaveClick}
          >
            {save.isPending ? 'Saving…' : 'Save changes'}
          </button>
        </div>
      </div>

      {/* Confirmation Dialog on Status Changes */}
      {showConfirmModal && (
        <StatusChangeConfirmDialog
          user={user}
          newStatus={accountStatus}
          reason={reason.trim()}
          onConfirm={handleConfirmDialogSubmit}
          onCancel={() => setShowConfirmModal(false)}
          isPending={save.isPending}
        />
      )}
    </>
  );
}

// ─── Users page ─────────────────────────────────────────────────────────────────
export function UsersPage() {
  const { user: currentUser } = useAuth();
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [selectedUser, setSelectedUser] = useState<AdminUserProfile | null>(null);
  const [liveSearch, setLiveSearch] = useState('');
  const [successMessage, setSuccessMessage] = useState('');

  // Debounce search by submitting on Enter or filter change only
  const usersQuery = useQuery({
    queryKey: usersKey(statusFilter, liveSearch),
    enabled: Boolean(currentUser?.accessToken),
    queryFn: ({ signal }) =>
      listUsers({ status: statusFilter || undefined, search: liveSearch || undefined }, signal),
  });

  function handleSearchSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const input =
      (e.currentTarget.elements.namedItem('user-search-input') as HTMLInputElement | null) ??
      e.currentTarget.querySelector<HTMLInputElement>('#user-search-input');
    const submittedSearch = input ? input.value : search;
    setSearch(submittedSearch);
    setLiveSearch(submittedSearch.trim());
  }

  function handleUserSaved(updated: AdminUserProfile, statusChanged: boolean) {
    setSelectedUser(null);
    const auditNote = statusChanged
      ? ` Account status changed to ${updated.accountStatus} and recorded in audit log.`
      : ' Changes recorded in audit trail.';
    setSuccessMessage(`User ${updated.name || updated.email} updated successfully.${auditNote}`);
    setTimeout(() => setSuccessMessage(''), 6000);
  }

  return (
    <main className="staff-main">
      <section className="staff-section">
        <div className="staff-section-heading">
          <div>
            <span className="staff-eyebrow">DISPATCH DESK</span>
            <h1>User management</h1>
            <p>View and manage registered user accounts.</p>
          </div>
        </div>

        {/* Success Alert Banner */}
        {successMessage && (
          <div
            className="staff-review-success"
            role="status"
            style={{
              padding: '12px 16px',
              borderRadius: '8px',
              border: '1px solid var(--line)',
              background: 'var(--cream)',
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              marginBottom: '16px',
            }}
          >
            <span>{successMessage}</span>
            <button
              type="button"
              className="staff-modal-close"
              style={{ fontSize: '16px', cursor: 'pointer' }}
              onClick={() => setSuccessMessage('')}
              aria-label="Dismiss message"
            >
              ✕
            </button>
          </div>
        )}

        {/* Filters */}
        <form
          className="staff-filter-bar"
          onSubmit={handleSearchSubmit}
          role="search"
          aria-label="Filter users"
        >
          <input
            id="user-search-input"
            name="user-search-input"
            type="search"
            value={search}
            aria-label="Search by name or email"
            placeholder="Search name or email…"
            onChange={(e) => setSearch(e.target.value)}
          />
          <select
            id="user-status-filter"
            aria-label="Filter by account status"
            value={statusFilter}
            onChange={(e) => {
              setStatusFilter(e.target.value);
              // Reset search when filter changes
              setLiveSearch(search.trim());
            }}
          >
            <option value="">All statuses</option>
            <option value="ACTIVE">Active</option>
            <option value="DISABLED">Disabled</option>
            <option value="SUSPENDED">Suspended</option>
          </select>
          <button type="submit" className="staff-review-button is-approve">
            Search
          </button>
          {(liveSearch || statusFilter) && (
            <button
              type="button"
              className="staff-review-button"
              onClick={() => {
                setSearch('');
                setLiveSearch('');
                setStatusFilter('');
              }}
            >
              Clear
            </button>
          )}
        </form>

        {/* Results */}
        <div className="staff-panel" style={{ marginTop: '16px' }}>
          {usersQuery.isPending ? (
            <div className="staff-empty">Loading users…</div>
          ) : usersQuery.isError ? (
            <div className="staff-error" role="alert">
              {friendlyApiError(usersQuery.error, 'Could not load users. Please try again.')}
            </div>
          ) : !usersQuery.data?.length ? (
            <div className="staff-empty">No users match the current filter.</div>
          ) : (
            <div className="staff-user-table" role="table" aria-label="Users">
              <div className="staff-user-thead" role="rowgroup">
                <div className="staff-user-row staff-user-row-head" role="row">
                  <span role="columnheader">Name / Email</span>
                  <span role="columnheader">Roles</span>
                  <span role="columnheader">Status</span>
                  <span role="columnheader">Merchant</span>
                  <span role="columnheader">Actions</span>
                </div>
              </div>
              <div role="rowgroup">
                {usersQuery.data.map((u) => (
                  <div className="staff-user-row" key={u.id} role="row">
                    <span role="cell">
                      <strong>{u.name ?? '—'}</strong>
                      <small>{u.email}</small>
                    </span>
                    <span role="cell" className="staff-role-list">
                      {u.roles.length > 0 ? (
                        u.roles.map((r) => <RolePill key={r} role={r} />)
                      ) : (
                        <span className="staff-muted">—</span>
                      )}
                    </span>
                    <span role="cell">
                      <StatusBadge status={u.accountStatus} />
                    </span>
                    <span role="cell">
                      <MerchantStatusBadge status={u.merchantStatus ?? null} />
                    </span>
                    <span role="cell">
                      <button
                        id={`edit-user-${u.id}`}
                        type="button"
                        aria-label={`Edit user ${u.name ?? u.email}`}
                        className="staff-review-button"
                        onClick={() => setSelectedUser(u)}
                      >
                        Edit
                      </button>
                    </span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>

        <p className="staff-review-notice" style={{ marginTop: '14px' }}>
          Role assignment is managed in Keycloak. Only name, phone, and account status can be
          updated here.
        </p>
      </section>

      {/* Edit panel overlay */}
      {selectedUser && currentUser && (
        <>
          <div
            className="staff-overlay"
            aria-hidden="true"
            onClick={() => setSelectedUser(null)}
          />
          <UserEditPanel
            user={selectedUser}
            currentUserId={currentUser.id}
            onClose={() => setSelectedUser(null)}
            onSaved={handleUserSaved}
          />
        </>
      )}
    </main>
  );
}
