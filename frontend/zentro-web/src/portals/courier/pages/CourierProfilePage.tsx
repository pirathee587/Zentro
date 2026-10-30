import React, { useState, useEffect } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/shared/auth/useAuth';
import { friendlyApiError } from '@/shared/api/apiClient';
import {
  getCourierProfile,
  provisionCourierProfile,
  updateCourierPersonalInfo,
  type CourierProfile,
} from '../api/courierApi';

export function CourierProfilePage() {
  const { user } = useAuth();
  const queryClient = useQueryClient();

  const [name, setName] = useState('');
  const [phone, setPhone] = useState('');
  const [formError, setFormError] = useState<string | null>(null);
  const [feedbackSuccess, setFeedbackSuccess] = useState<string | null>(null);
  const [feedbackError, setFeedbackError] = useState<string | null>(null);

  const profileQuery = useQuery({
    queryKey: ['courier', 'self-profile'],
    enabled: Boolean(user?.accessToken),
    queryFn: () => getCourierProfile(user?.accessToken),
    retry: false,
  });

  useEffect(() => {
    if (profileQuery.data) {
      setName(profileQuery.data.name || '');
      setPhone(profileQuery.data.phone || '');
    }
  }, [profileQuery.data]);

  const provisionMutation = useMutation({
    mutationFn: () => provisionCourierProfile(user?.accessToken),
    onSuccess: async () => {
      setFeedbackSuccess('Courier profile successfully initialized.');
      setFeedbackError(null);
      await queryClient.invalidateQueries({ queryKey: ['courier', 'self-profile'] });
    },
    onError: (err) => {
      setFeedbackError(friendlyApiError(err, 'Failed to initialize courier profile. Please try again.'));
    },
  });

  const updateMutation = useMutation({
    mutationFn: (data: { name: string; phone: string }) =>
      updateCourierPersonalInfo(data, user?.accessToken),
    onSuccess: async () => {
      setFeedbackSuccess('Your personal details have been updated successfully.');
      setFeedbackError(null);
      await queryClient.invalidateQueries({ queryKey: ['courier', 'self-profile'] });
    },
    onError: (err) => {
      setFeedbackError(friendlyApiError(err, 'Could not save profile changes. Please try again.'));
    },
  });

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setFeedbackSuccess(null);
    setFeedbackError(null);
    setFormError(null);

    const trimmedName = name.trim();
    const trimmedPhone = phone.trim();

    if (!trimmedName) {
      setFormError('Display name cannot be empty.');
      return;
    }
    if (trimmedName.length > 200) {
      setFormError('Display name must not exceed 200 characters.');
      return;
    }
    if (trimmedPhone.length > 32) {
      setFormError('Phone number must not exceed 32 characters.');
      return;
    }

    updateMutation.mutate({ name: trimmedName, phone: trimmedPhone });
  };

  const formatDate = (isoStr?: string) => {
    if (!isoStr) return '—';
    const date = new Date(isoStr);
    return Number.isNaN(date.getTime())
      ? '—'
      : new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(date);
  };

  const profile = profileQuery.data;
  const is404 =
    profileQuery.isError &&
    (profileQuery.error as { response?: { status?: number } })?.response?.status === 404;

  return (
    <main className="courier-main" aria-labelledby="courier-profile-title">
      <section className="staff-hero">
        <div>
          <span className="staff-eyebrow">COURIER IDENTITY & OPERATIONS</span>
          <h1 id="courier-profile-title">Courier Profile</h1>
          <p>
            Review your operational assignment details, vehicle status, and manage your personal contact
            information.
          </p>
        </div>
        {profile && (
          <span
            className={`courier-badge ${profile.active ? 'is-active' : 'is-inactive'}`}
            role="status"
            aria-label={`Eligibility status: ${profile.active ? 'Active' : 'Inactive'}`}
          >
            <i className="courier-badge-dot" aria-hidden="true" />
            {profile.active ? 'Eligible for Dispatch' : 'Inactive / Ineligible'}
          </span>
        )}
      </section>

      {/* Success / Error Banners */}
      {feedbackSuccess && (
        <div className="courier-alert is-success" role="alert" style={{ marginTop: 24 }}>
          <span>{feedbackSuccess}</span>
          <button
            type="button"
            className="courier-dismiss-btn"
            onClick={() => setFeedbackSuccess(null)}
          >
            Dismiss
          </button>
        </div>
      )}

      {feedbackError && (
        <div className="courier-alert is-error" role="alert" style={{ marginTop: 24 }}>
          <span>{feedbackError}</span>
          <button
            type="button"
            className="courier-dismiss-btn"
            onClick={() => setFeedbackError(null)}
          >
            Dismiss
          </button>
        </div>
      )}

      {/* Loading state */}
      {profileQuery.isPending && (
        <section className="courier-section" aria-busy="true">
          <div className="courier-card" style={{ alignItems: 'center', justifyContent: 'center', minHeight: 200 }}>
            <p className="courier-field-label">Loading your courier profile…</p>
          </div>
        </section>
      )}

      {/* Missing profile / 404 state */}
      {is404 && (
        <section className="courier-section">
          <div className="courier-assignment">
            <span className="courier-assignment-icon" aria-hidden="true">
              !
            </span>
            <div>
              <h2>No courier profile registered yet</h2>
              <p>
                Your user account has the Courier role, but a courier profile has not been initialized.
                Click the button below to provision your profile and connect to dispatch operations.
              </p>
              <div style={{ marginTop: 18 }}>
                <button
                  type="button"
                  className="courier-submit-btn"
                  disabled={provisionMutation.isPending}
                  onClick={() => provisionMutation.mutate()}
                >
                  {provisionMutation.isPending ? 'Provisioning…' : 'Initialize Courier Profile'}
                </button>
              </div>
            </div>
          </div>
        </section>
      )}

      {/* Error state (non-404) */}
      {profileQuery.isError && !is404 && (
        <section className="courier-section">
          <div className="courier-alert is-error" role="alert">
            <span>{friendlyApiError(profileQuery.error, 'Could not retrieve your courier profile.')}</span>
            <button
              type="button"
              className="staff-text-link"
              onClick={() => void profileQuery.refetch()}
            >
              Retry
            </button>
          </div>
        </section>
      )}

      {/* Loaded profile cards */}
      {profile && (
        <div className="courier-profile-grid">
          {/* Card 1: Editable Personal Details */}
          <article className="courier-card">
            <div className="courier-card-header">
              <div>
                <h2>Personal Details</h2>
                <p>Manage your public contact information shown to customers and dispatchers.</p>
              </div>
            </div>

            <form className="courier-form" onSubmit={handleSubmit} noValidate>
              <div className="courier-form-group">
                <label htmlFor="courier-name-input">Full Name</label>
                <input
                  id="courier-name-input"
                  className={`courier-input ${formError ? 'courier-input-error' : ''}`}
                  type="text"
                  value={name}
                  onChange={(e) => {
                    setName(e.target.value);
                    if (formError) setFormError(null);
                  }}
                  maxLength={200}
                  required
                  aria-describedby={formError ? 'courier-name-error' : undefined}
                />
                {formError && (
                  <span id="courier-name-error" className="courier-form-error" role="alert">
                    {formError}
                  </span>
                )}
              </div>

              <div className="courier-form-group">
                <label htmlFor="courier-phone-input">Phone Number</label>
                <input
                  id="courier-phone-input"
                  className="courier-input"
                  type="tel"
                  value={phone}
                  onChange={(e) => setPhone(e.target.value)}
                  maxLength={32}
                  placeholder="+947XXXXXXXX"
                />
                <span className="courier-form-hint">Format: international or local phone number.</span>
              </div>

              <div className="courier-form-group">
                <label htmlFor="courier-email-input">Account Email (Read-only)</label>
                <input
                  id="courier-email-input"
                  className="courier-input"
                  type="email"
                  value={profile.email}
                  disabled
                  readOnly
                  aria-readonly="true"
                />
                <span className="courier-form-hint">Email address is managed via your Keycloak identity.</span>
              </div>

              <div style={{ marginTop: 10 }}>
                <button
                  type="submit"
                  className="courier-submit-btn"
                  disabled={updateMutation.isPending}
                >
                  {updateMutation.isPending ? 'Saving Changes…' : 'Save Personal Details'}
                </button>
              </div>
            </form>
          </article>

          {/* Card 2: Read-Only Dispatch & Operational Attributes */}
          <article className="courier-card">
            <div className="courier-card-header">
              <div>
                <h2>Operational Attributes</h2>
                <p>Assigned and maintained by the Zentro Dispatch operations desk.</p>
              </div>
            </div>

            <div className="courier-field-row">
              <span className="courier-field-label">Eligibility Status</span>
              <span
                className={`courier-badge ${profile.active ? 'is-active' : 'is-inactive'}`}
              >
                {profile.active ? 'Active & Approved' : 'Ineligible for Jobs'}
              </span>
            </div>

            <div className="courier-field-row">
              <span className="courier-field-label">Account Status</span>
              <span className="courier-badge is-status">{profile.accountStatus || 'ACTIVE'}</span>
            </div>

            <div className="courier-field-row">
              <span className="courier-field-label">Assigned Zone</span>
              <span className="courier-field-val">
                {profile.assignedZone ? (
                  <span className="courier-badge is-tag">{profile.assignedZone}</span>
                ) : (
                  <span style={{ color: 'var(--muted)', fontWeight: 400 }}>Unassigned</span>
                )}
              </span>
            </div>

            <div className="courier-field-row">
              <span className="courier-field-label">Vehicle Type</span>
              <span className="courier-field-val">
                {profile.vehicleType ? (
                  <span className="courier-badge is-tag">{profile.vehicleType}</span>
                ) : (
                  <span style={{ color: 'var(--muted)', fontWeight: 400 }}>Not Specified</span>
                )}
              </span>
            </div>

            <div className="courier-field-row">
              <span className="courier-field-label">Courier ID</span>
              <code style={{ fontSize: 11, color: 'var(--muted)', fontFamily: 'monospace' }}>
                {profile.id}
              </code>
            </div>

            <div className="courier-field-row">
              <span className="courier-field-label">Member Since</span>
              <span className="courier-field-val" style={{ fontWeight: 500, fontSize: 12 }}>
                {formatDate(profile.createdAt)}
              </span>
            </div>

            <div className="courier-field-row">
              <span className="courier-field-label">Last Updated</span>
              <span className="courier-field-val" style={{ fontWeight: 500, fontSize: 12 }}>
                {formatDate(profile.updatedAt)}
              </span>
            </div>

            <div className="courier-notice-box">
              <strong>Notice:</strong> Operational parameters including assignment zone, vehicle category, and
              dispatch availability status are controlled centrally by dispatchers. If your zone or vehicle
              requires modification, contact operations support.
            </div>
          </article>
        </div>
      )}
    </main>
  );
}
