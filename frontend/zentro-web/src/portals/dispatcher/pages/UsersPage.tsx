import { useState, useId, useRef, type FormEvent } from 'react';
import { useDialogFocus } from '@/shared/ui/useDialogFocus';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/shared/auth/useAuth';
import { friendlyApiError } from '@/shared/api/apiClient';
import {
  listUsers,
  updateUser,
  deleteUser,
  createCourier,
  type AdminUserProfile,
  type AdminUpdateUserRequest,
  type CreateCourierRequest,
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

// ─── Add Courier Modal ──────────────────────────────────────────────────────────
function AddCourierModal({
  onClose,
  onCreated,
}: {
  onClose: () => void;
  onCreated: (email: string) => void;
}) {
  const dialog = useRef<HTMLDivElement>(null);
  useDialogFocus(dialog, onClose);

  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [phone, setPhone] = useState('');
  const [assignedZone, setAssignedZone] = useState('Colombo Central');
  const [vehicleType, setVehicleType] = useState('MOTORCYCLE');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError('');
    if (!name.trim()) {
      setError('Please enter courier name.');
      return;
    }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) {
      setError('Please enter a valid Gmail / email address.');
      return;
    }

    setLoading(true);
    try {
      await createCourier({
        name: name.trim(),
        email: email.trim().toLowerCase(),
        phone: phone.trim() || undefined,
        assignedZone,
        vehicleType,
      });
      onCreated(email.trim());
    } catch (err) {
      setError(friendlyApiError(err, 'Failed to create courier. Please check details and try again.'));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div
      role="dialog"
      ref={dialog}
      tabIndex={-1}
      aria-modal="true"
      aria-label="Add New Courier"
      className="staff-edit-panel"
      style={{ maxWidth: '520px' }}
    >
      <div className="staff-edit-panel-header">
        <div>
          <span className="staff-eyebrow">STAFF ONBOARDING</span>
          <h2>Add New Courier</h2>
          <small>Assign courier with their real Gmail. A temporary password will be emailed.</small>
        </div>
        <button type="button" aria-label="Close" className="staff-close-btn" onClick={onClose}>
          ✕
        </button>
      </div>

      <form onSubmit={handleSubmit} className="staff-edit-panel-body">
        <div className="staff-field">
          <label htmlFor="courier-name">Full Name *</label>
          <input
            id="courier-name"
            type="text"
            required
            value={name}
            placeholder="e.g. Priyantha Silva"
            onChange={(e) => setName(e.target.value)}
          />
        </div>

        <div className="staff-field">
          <label htmlFor="courier-email">Courier Real Gmail *</label>
          <input
            id="courier-email"
            type="email"
            required
            value={email}
            placeholder="courier@gmail.com"
            onChange={(e) => setEmail(e.target.value)}
          />
          <small style={{ color: '#766757', marginTop: '4px', display: 'block', fontSize: '12px' }}>
            A temporary password will be sent to this email. Keycloak will mandate changing password on first login.
          </small>
        </div>

        <div className="staff-field">
          <label htmlFor="courier-phone">Phone Number</label>
          <input
            id="courier-phone"
            type="tel"
            value={phone}
            placeholder="07XXXXXXXX or +947XXXXXXXX"
            onChange={(e) => setPhone(e.target.value)}
          />
        </div>

        <div className="staff-field">
          <label htmlFor="courier-zone">Assigned Delivery Zone</label>
          <select id="courier-zone" value={assignedZone} onChange={(e) => setAssignedZone(e.target.value)}>
            <option value="Colombo Central">Colombo Central</option>
            <option value="Colombo North">Colombo North</option>
            <option value="Colombo South">Colombo South</option>
            <option value="Dehiwala-Mount Lavinia">Dehiwala-Mount Lavinia</option>
            <option value="Moratuwa">Moratuwa</option>
            <option value="Kaduwela">Kaduwela</option>
            <option value="Gampaha">Gampaha</option>
            <option value="Negombo">Negombo</option>
            <option value="Kandy">Kandy</option>
            <option value="Galle">Galle</option>
            <option value="Jaffna">Jaffna</option>
            <option value="Kurunegala">Kurunegala</option>
          </select>
        </div>

        <div className="staff-field">
          <label htmlFor="courier-vehicle">Vehicle Type</label>
          <select id="courier-vehicle" value={vehicleType} onChange={(e) => setVehicleType(e.target.value)}>
            <option value="MOTORCYCLE">Motorcycle</option>
            <option value="VAN">Van</option>
            <option value="THREE_WHEELER">Three-Wheeler</option>
            <option value="CAR">Car</option>
            <option value="BICYCLE">Bicycle</option>
          </select>
        </div>

        {error && <div className="staff-error" role="alert" style={{ marginBottom: '12px' }}>{error}</div>}

        <div style={{ display: 'flex', gap: '10px', marginTop: '16px' }}>
          <button type="submit" className="staff-review-button is-approve" disabled={loading} style={{ flex: 1 }}>
            {loading ? 'Creating & Sending Email...' : 'Create Courier & Send Password'}
          </button>
          <button type="button" className="staff-review-button" onClick={onClose}>
            Cancel
          </button>
        </div>
      </form>
    </div>
  );
}

// ─── Delete Confirmation Modal ─────────────────────────────────────────────────
function DeleteUserModal({
  user,
  onClose,
  onDeleted,
}: {
  user: AdminUserProfile;
  onClose: () => void;
  onDeleted: () => void;
}) {
  const dialog = useRef<HTMLDivElement>(null);
  useDialogFocus(dialog, onClose);

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const handleDelete = async () => {
    setLoading(true);
    setError('');
    try {
      await deleteUser(user.id);
      onDeleted();
    } catch (err) {
      setError(friendlyApiError(err, 'Failed to delete user account.'));
      setLoading(false);
    }
  };

  return (
    <div
      role="dialog"
      ref={dialog}
      tabIndex={-1}
      aria-modal="true"
      aria-label="Confirm User Deletion"
      className="staff-edit-panel"
      style={{ maxWidth: '440px' }}
    >
      <div className="staff-edit-panel-header">
        <div>
          <span className="staff-eyebrow" style={{ color: '#c53030' }}>DELETE ACCOUNT</span>
          <h2>Delete User?</h2>
        </div>
        <button type="button" aria-label="Close" className="staff-close-btn" onClick={onClose}>
          ✕
        </button>
      </div>

      <div className="staff-edit-panel-body">
        <p style={{ color: '#231d19', fontSize: '14px', lineHeight: 1.6 }}>
          Are you sure you want to permanently delete <strong>{user.name || user.email}</strong> (<code>{user.email}</code>)?
        </p>
        <p style={{ color: '#8c2525', fontSize: '13px', background: '#fff5f5', padding: '10px', borderRadius: '6px', border: '1px solid #fed7d7' }}>
          This will permanently delete the user in Keycloak and remove all associated profile data in Zentro.
        </p>

        {error && <div className="staff-error" role="alert" style={{ marginBottom: '12px' }}>{error}</div>}

        <div style={{ display: 'flex', gap: '10px', marginTop: '16px' }}>
          <button
            type="button"
            className="staff-review-button is-reject"
            disabled={loading}
            onClick={handleDelete}
            style={{ flex: 1, background: '#c53030', color: '#fff', borderColor: '#c53030' }}
          >
            {loading ? 'Deleting...' : 'Yes, Delete Account'}
          </button>
          <button type="button" className="staff-review-button" onClick={onClose}>
            Cancel
          </button>
        </div>
      </div>
    </div>
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
  const dialog = useRef<HTMLDivElement>(null);
  useDialogFocus(dialog, () => { if (!save.isPending) onClose(); });

  const save = useMutation({
    mutationFn: () => {
      const request: AdminUpdateUserRequest = {};
      if (name.trim()) request.name = name.trim();
      if (phone.trim()) request.phone = phone.trim();
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
      ref={dialog}
      tabIndex={-1}
      aria-modal="true"
      aria-label="Edit user"
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
            <small style={{ color: '#888', marginTop: '4px', display: 'block' }}>
              You cannot change your own account status.
            </small>
          )}
        </div>

        <div className="staff-field">
          <span className="staff-label">Assigned roles</span>
          <div className="staff-role-list" style={{ marginTop: '4px' }}>
            {user.roles.length > 0 ? (
              user.roles.map((r) => <RolePill key={r} role={r} />)
            ) : (
              <span className="staff-muted">No roles</span>
            )}
          </div>
          <small style={{ color: '#888', marginTop: '4px', display: 'block' }}>
            Roles are assigned in Keycloak and read-only here.
          </small>
        </div>

        {saveError && (
          <div className="staff-error" role="alert" style={{ marginBottom: '12px' }}>
            {saveError}
          </div>
        )}

        <div style={{ display: 'flex', gap: '8px', marginTop: '16px' }}>
          <button
            type="button"
            className="staff-review-button is-approve"
            disabled={save.isPending}
            onClick={() => save.mutate()}
          >
            {save.isPending ? 'Saving…' : 'Save changes'}
          </button>
          <button
            type="button"
            className="staff-review-button"
            disabled={save.isPending}
            onClick={onClose}
          >
            Cancel
          </button>
        </div>
      </div>
    </div>
  );
}

// ─── Main page component ───────────────────────────────────────────────────────
export function UsersPage() {
  const { user: currentUser } = useAuth();
  const queryClient = useQueryClient();

  const [statusFilter, setStatusFilter] = useState('');
  const [search, setSearch] = useState('');
  const [liveSearch, setLiveSearch] = useState('');
  const [selectedUser, setSelectedUser] = useState<AdminUserProfile | null>(null);
  const [userToDelete, setUserToDelete] = useState<AdminUserProfile | null>(null);
  const [isAddCourierOpen, setIsAddCourierOpen] = useState(false);
  const [bannerNotice, setBannerNotice] = useState('');

  const usersQuery = useQuery({
    queryKey: usersKey(statusFilter, liveSearch),
    queryFn: ({ signal }) =>
      listUsers(
        {
          status: statusFilter || undefined,
          search: liveSearch || undefined,
        },
        signal,
      ),
  });

  const handleSearchSubmit = (e: FormEvent) => {
    e.preventDefault();
    setLiveSearch(search.trim());
  };

  return (
    <main className="staff-page">
      <section className="staff-section" aria-labelledby="users-heading">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', flexWrap: 'wrap', gap: '12px' }}>
          <div>
            <span className="staff-eyebrow">DISPATCH DESK</span>
            <h1 id="users-heading">User management</h1>
            <p>View and manage registered user accounts.</p>
          </div>
          <button
            type="button"
            className="staff-review-button is-approve"
            style={{ fontWeight: 600, padding: '10px 18px' }}
            onClick={() => setIsAddCourierOpen(true)}
          >
            + Add Courier
          </button>
        </div>

        {bannerNotice && (
          <div
            style={{
              marginTop: '16px',
              padding: '12px 16px',
              background: '#e6f6ee',
              color: '#0e6245',
              border: '1px solid #b7ebcf',
              borderRadius: '8px',
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
            }}
          >
            <span>{bannerNotice}</span>
            <button
              type="button"
              onClick={() => setBannerNotice('')}
              style={{ background: 'none', border: 'none', color: '#0e6245', cursor: 'pointer', fontWeight: 'bold' }}
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
          style={{ marginTop: '16px' }}
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
                {usersQuery.data.map((u) => {
                  const isSelf = u.id === currentUser?.id;
                  return (
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
                      <span role="cell" style={{ display: 'flex', gap: '6px' }}>
                        <button
                          id={`edit-user-${u.id}`}
                          type="button"
                          aria-label={`Edit user ${u.name ?? u.email}`}
                          className="staff-review-button"
                          onClick={() => setSelectedUser(u)}
                        >
                          Edit
                        </button>
                        <button
                          id={`delete-user-${u.id}`}
                          type="button"
                          aria-label={`Delete user ${u.name ?? u.email}`}
                          className="staff-review-button is-reject"
                          disabled={isSelf}
                          title={isSelf ? 'You cannot delete your own account' : 'Delete user'}
                          onClick={() => setUserToDelete(u)}
                        >
                          Delete
                        </button>
                      </span>
                    </div>
                  );
                })}
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

      {/* Add Courier Modal */}
      {isAddCourierOpen && (
        <>
          <div
            className="staff-overlay"
            aria-hidden="true"
            onClick={() => setIsAddCourierOpen(false)}
          />
          <AddCourierModal
            onClose={() => setIsAddCourierOpen(false)}
            onCreated={(courierEmail) => {
              setIsAddCourierOpen(false);
              setBannerNotice(`Courier created! Temporary login credentials have been sent to ${courierEmail}.`);
              void queryClient.invalidateQueries({ queryKey: ['dispatcher', 'admin-users'] });
            }}
          />
        </>
      )}

      {/* Delete Confirmation Modal */}
      {userToDelete && (
        <>
          <div
            className="staff-overlay"
            aria-hidden="true"
            onClick={() => setUserToDelete(null)}
          />
          <DeleteUserModal
            user={userToDelete}
            onClose={() => setUserToDelete(null)}
            onDeleted={() => {
              const deletedEmail = userToDelete.email;
              setUserToDelete(null);
              setBannerNotice(`User ${deletedEmail} has been deleted.`);
              void queryClient.invalidateQueries({ queryKey: ['dispatcher', 'admin-users'] });
            }}
          />
        </>
      )}
    </main>
  );
}
