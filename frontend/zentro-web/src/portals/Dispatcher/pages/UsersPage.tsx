import { useState, useId, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/shared/auth/useAuth';
import { friendlyApiError } from '@/shared/api/apiClient';
import {
  listUsers,
  updateUser,
  type AdminUserProfile,
  type AdminUpdateUserRequest,
} from '../api/adminApi';

// ─── Query key ─────────────────────────────────────────────────────────────────
const usersKey = (status: string, search: string) =>
  ['dispatcher', 'admin-users', status, search] as const;

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

// ─── User edit panel ────────────────────────────────────────────────────────────
function UserEditPanel({
  user,
  currentUserId,
  onClose,
  onSaved,
}: {
  user: AdminUserProfile;
  currentUserId: string;
  onClose: () => void;
  onSaved: () => void;
}) {
  const nameId = useId();
  const phoneId = useId();
  const statusId = useId();

  const queryClient = useQueryClient();
  const [name, setName] = useState(user.name ?? '');
  const [phone, setPhone] = useState(user.phone ?? '');
  const [accountStatus, setAccountStatus] = useState<AdminUpdateUserRequest['accountStatus']>(
    user.accountStatus,
  );
  const [saveError, setSaveError] = useState('');
  const isSelf = user.id === currentUserId;

  const save = useMutation({
    mutationFn: () => {
      const request: AdminUpdateUserRequest = {};
      if (name.trim()) request.name = name.trim();
      if (phone.trim()) request.phone = phone.trim();
      // Self-lockout guard: the backend also enforces this, but we validate here too.
      if (!isSelf || accountStatus === 'ACTIVE') {
        request.accountStatus = accountStatus;
      }
      return updateUser(user.id, request);
    },
    onSuccess: async () => {
      setSaveError('');
      await queryClient.invalidateQueries({ queryKey: ['dispatcher', 'admin-users'] });
      onSaved();
    },
    onError: (error) =>
      setSaveError(friendlyApiError(error, 'Could not update the user. Please try again.')),
  });

  return (
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
        <button type="button" aria-label="Close" className="staff-close-btn" onClick={onClose}>
          ✕
        </button>
      </div>

      <div className="staff-edit-panel-body">
        <div className="staff-field">
          <label htmlFor={nameId}>Display name</label>
          <input
            id={nameId}
            type="text"
            value={name}
            maxLength={200}
            placeholder={user.email}
            onChange={(e) => setName(e.target.value)}
          />
        </div>

        <div className="staff-field">
          <label htmlFor={phoneId}>Phone</label>
          <input
            id={phoneId}
            type="tel"
            value={phone}
            maxLength={32}
            placeholder="Not set"
            onChange={(e) => setPhone(e.target.value)}
          />
        </div>

        <div className="staff-field">
          <label htmlFor={statusId}>Account status</label>
          <select
            id={statusId}
            value={accountStatus}
            disabled={isSelf}
            onChange={(e) =>
              setAccountStatus(e.target.value as AdminUpdateUserRequest['accountStatus'])
            }
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
        </div>

        <div className="staff-field-row">
          <strong>Roles</strong>
          <div className="staff-role-list">
            {user.roles.length > 0
              ? user.roles.map((r) => <RolePill key={r} role={r} />)
              : <span className="staff-muted">No roles assigned</span>}
          </div>
          <small className="staff-field-hint">Role assignment is managed in Keycloak.</small>
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
          onClick={() => void save.mutate()}
        >
          {save.isPending ? 'Saving…' : 'Save changes'}
        </button>
      </div>
    </div>
  );
}

// ─── Users page ─────────────────────────────────────────────────────────────────
export function UsersPage() {
  const { user: currentUser } = useAuth();
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [selectedUser, setSelectedUser] = useState<AdminUserProfile | null>(null);
  const [liveSearch, setLiveSearch] = useState('');

  // Debounce search by submitting on Enter or filter change only
  const usersQuery = useQuery({
    queryKey: usersKey(statusFilter, liveSearch),
    enabled: Boolean(currentUser?.accessToken),
    retry: false,
    queryFn: () => listUsers({ status: statusFilter || undefined, search: liveSearch || undefined }),
  });

  function handleSearchSubmit(e: FormEvent) {
    e.preventDefault();
    setLiveSearch(search.trim());
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

        {/* Filters */}
        <form
          className="staff-filter-bar"
          onSubmit={handleSearchSubmit}
          role="search"
          aria-label="Filter users"
        >
          <input
            id="user-search-input"
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
                      {u.roles.length > 0
                        ? u.roles.map((r) => <RolePill key={r} role={r} />)
                        : <span className="staff-muted">—</span>}
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
            onSaved={() => setSelectedUser(null)}
          />
        </>
      )}
    </main>
  );
}
