import { createPortal } from 'react-dom';
import axios from 'axios';
import { X } from 'lucide-react';
import './UsersPage.css';
import { useState, useId, useRef, useEffect, type FormEvent } from 'react';
import { useDialogFocus } from '@/shared/ui/useDialogFocus';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/shared/auth/useAuth';
import { friendlyApiError } from '@/shared/api/apiClient';
import {
  listUsers,
  updateUser,
  deleteUser,
  type AdminUserProfile,
  type AdminUpdateUserRequest,
} from '../api/adminApi';
import { AddCourierModal } from '../components/AddCourierModal';

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
  return <span className={`staff-badge ${map[status] ?? 'staff-badge-unknown'}`}>{status}</span>;
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
  const { user: currentUser } = useAuth();

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const isSelf = user.id === currentUser?.userId || (Boolean(currentUser?.email) && user.email.toLowerCase() === currentUser?.email?.toLowerCase());

  const handleDelete = async () => {
    if (isSelf) {
      setError('You cannot delete your own account.');
      return;
    }
    setLoading(true);
    setError('');
    try {
      await deleteUser(user.id);
      onDeleted();
    } catch (err) {
      if (axios.isAxiosError(err) && (err.response?.status === 404 || err.response?.status === 204)) {
        onDeleted();
        return;
      }
      const problem = (err as { response?: { data?: { detail?: string; message?: string } } })?.response?.data;
      const detail = problem?.detail || problem?.message;
      setError(detail || friendlyApiError(err, 'Failed to delete user account.'));
      setLoading(false);
    }
  };

  return createPortal(
    <div
      className="user-edit-backdrop"
      data-testid="delete-user-backdrop"
      onClick={(e) => {
        if (e.target === e.currentTarget && !loading) onClose();
      }}
    >
      <div
        role="dialog"
        ref={dialog}
        tabIndex={-1}
        aria-modal="true"
        aria-label="Confirm User Deletion"
        className="staff-edit-panel user-edit-dialog"
        style={{ maxWidth: '440px' }}
      >
        <div className="staff-edit-panel-header">
          <div>
            <span className="staff-eyebrow" style={{ color: 'var(--err)' }}>DELETE ACCOUNT</span>
            <h2>Delete User?</h2>
          </div>
          <button type="button" aria-label="Close" className="staff-close-btn" disabled={loading} onClick={onClose}>
            <X size={20} aria-hidden="true" />
          </button>
        </div>

        <div className="staff-edit-panel-body">
          <p style={{ color: 'var(--espresso)', fontSize: '14px', lineHeight: 1.6 }}>
            Are you sure you want to permanently delete <strong>{user.name || user.email}</strong> (<code>{user.email}</code>)?
          </p>
          <p style={{ color: 'var(--err)', fontSize: '13px', background: 'var(--warm-pale)', padding: '10px', borderRadius: '6px', border: '1px solid var(--warm-pale)' }}>
            This will permanently delete the user in Keycloak and remove all associated profile data in Zentro.
          </p>

          {error && <div className="staff-error" role="alert" style={{ marginBottom: '12px' }}>{error}</div>}

          <div className="staff-edit-panel-footer" style={{ marginTop: '16px' }}>
            <button type="button" className="staff-review-button" disabled={loading} onClick={onClose}>
              Cancel
            </button>
            <button
              type="button"
              className="staff-review-button is-reject"
              disabled={loading}
              onClick={handleDelete}
              style={{ flex: 1, background: 'var(--err)', color: 'var(--white)', borderColor: 'var(--err)' }}
            >
              {loading ? 'Deleting...' : 'Yes, Delete Account'}
            </button>
          </div>
        </div>
      </div>
    </div>,
    document.body,
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
  const emailId = useId();
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
  useEffect(() => {
    const overflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = overflow;
    };
  }, []);
  const dialog = useRef<HTMLDivElement>(null);
  useDialogFocus(dialog, () => {
    if (!save.isPending) onClose();
  });

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

  return createPortal(
    <div
      className="user-edit-backdrop"
      data-testid="user-edit-backdrop"
      onClick={(event) => {
        if (event.target === event.currentTarget && !save.isPending) onClose();
      }}
    >
      <div
        role="dialog"
        ref={dialog}
        tabIndex={-1}
        aria-modal="true"
        aria-label="Edit user"
        className="staff-edit-panel user-edit-dialog"
      >
        <div className="staff-edit-panel-header">
          <div>
            <span className="staff-eyebrow">ACCOUNT DETAILS</span>
            <h2>Edit user</h2>
            <small>{user.email}</small>
          </div>
          <button
            type="button"
            aria-label="Close"
            className="staff-close-btn"
            disabled={save.isPending}
            onClick={onClose}
          >
            <X size={20} aria-hidden="true" />
          </button>
        </div>

        <form
          className="user-edit-form"
          onSubmit={(event) => {
            event.preventDefault();
            if (!save.isPending) save.mutate();
          }}
        >
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
              <label htmlFor={emailId}>Email address</label>
              <input id={emailId} type="email" readOnly value={user.email} />
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
                {user.roles.length > 0 ? (
                  user.roles.map((r) => <RolePill key={r} role={r} />)
                ) : (
                  <span className="staff-muted">No roles assigned</span>
                )}
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
            <button
              type="button"
              className="staff-review-button"
              disabled={save.isPending}
              onClick={onClose}
            >
              Cancel
            </button>
            <button
              type="submit"
              className="staff-review-button is-approve"
              disabled={save.isPending}
            >
              {save.isPending ? 'Saving…' : 'Save changes'}
            </button>
          </div>
        </form>
      </div>
    </div>,
    document.body,
  );
}

// ─── Main page component ───────────────────────────────────────────────────────
export function UsersPage({ embedded = false }: { embedded?: boolean } = {}) {
  const Container = embedded ? 'div' : 'main';
  const Heading = embedded ? 'h2' : 'h1';
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
    enabled: Boolean(currentUser?.accessToken),
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
    <Container className={`staff-main users-page ${embedded ? 'people-embedded' : ''}`}>
      <section className="staff-section" aria-labelledby="users-heading">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', flexWrap: 'wrap', gap: '12px' }}>
          <div>
            <span className="staff-eyebrow">DISPATCH DESK</span>
            <Heading id="users-heading">User management</Heading>
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
              background: 'var(--warm-pale)',
              color: 'var(--umber)',
              border: '1px solid var(--warm-pale)',
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
              style={{ background: 'none', border: 'none', color: 'var(--umber)', cursor: 'pointer', fontWeight: 'bold' }}
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
                  const isSelf = u.id === currentUser?.userId || (Boolean(currentUser?.email) && u.email.toLowerCase() === currentUser?.email?.toLowerCase());
                  return (
                    <div className="staff-user-row" key={u.id} role="row">
                      <span role="cell" className="staff-user-identity">
                        {u.name?.trim() ? (
                          <>
                            <strong className="staff-user-name">{u.name.trim()}</strong>
                            <small className="staff-user-email">{u.email}</small>
                          </>
                        ) : (
                          <>
                            <strong className="staff-user-name">{u.email}</strong>
                            <small className="staff-user-email staff-user-placeholder" aria-hidden="true">
                              &nbsp;
                            </small>
                          </>
                        )}
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
                      <span role="cell" className="staff-user-actions">
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
        <UserEditPanel
          user={selectedUser}
          currentUserId={currentUser.id}
          onClose={() => setSelectedUser(null)}
          onSaved={() => {
            setSelectedUser(null);
            setBannerNotice('User updated successfully.');
          }}
        />
      )}

      {/* Add Courier Modal */}
      {isAddCourierOpen && (
        <AddCourierModal
          onClose={() => setIsAddCourierOpen(false)}
          onCreated={(courierEmail) => {
            setIsAddCourierOpen(false);
            setBannerNotice(`Courier created! Temporary login credentials have been sent to ${courierEmail}.`);
            void queryClient.invalidateQueries({ queryKey: ['dispatcher', 'admin-users'] });
          }}
        />
      )}

      {/* Delete Confirmation Modal */}
      {userToDelete && (
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
      )}
    </Container>
  );
}
