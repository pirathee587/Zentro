import React, { useState, useEffect } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/shared/auth/useAuth';
import { friendlyApiError } from '@/shared/api/apiClient';
import { Button, Input, Select } from '@/shared/ui';
import {
  getCourierProfile,
  provisionCourierProfile,
  updateCourierPersonalInfo,
} from '../api/courierApi';
import { principalCacheScope, queryKeys } from '@/shared/api/queryKeys';

export function CourierProfilePage() {
  const { user } = useAuth();
  const profileKey = queryKeys.courier.profile(principalCacheScope(user?.id));
  const queryClient = useQueryClient();

  const [name, setName] = useState('Saman Perera');
  const [phone, setPhone] = useState('0771234567');
  const [email, setEmail] = useState('saman.perera@example.com');
  const [zone, setZone] = useState('Colombo');
  const [vehicle, setVehicle] = useState('Motorbike');
  const [plateNumber, setPlateNumber] = useState('BCD-1234');

  const [formError, setFormError] = useState<string | null>(null);
  const [feedbackSuccess, setFeedbackSuccess] = useState<string | null>(null);
  const [feedbackError, setFeedbackError] = useState<string | null>(null);

  const profileQuery = useQuery({
    queryKey: profileKey,
    enabled: Boolean(user?.accessToken),
    queryFn: ({ signal }) => getCourierProfile(user?.accessToken, signal),
  });

  useEffect(() => {
    if (profileQuery.data) {
      if (profileQuery.data.name) setName(profileQuery.data.name);
      if (profileQuery.data.phone) setPhone(profileQuery.data.phone);
      if (profileQuery.data.email) setEmail(profileQuery.data.email);
      if (profileQuery.data.assignedZone) setZone(profileQuery.data.assignedZone);
      if (profileQuery.data.vehicleType) {
        const vt = profileQuery.data.vehicleType.toUpperCase();
        if (vt.includes('MOTOR') || vt.includes('BIKE')) setVehicle('Motorbike');
        else if (vt.includes('VAN')) setVehicle('Van');
        else if (vt.includes('THREE') || vt.includes('TUK')) setVehicle('Three-wheeler');
        else if (vt.includes('BICYCLE')) setVehicle('Bicycle');
      }
    }
  }, [profileQuery.data]);

  const provisionMutation = useMutation({
    mutationFn: () => provisionCourierProfile(user?.accessToken),
    onSuccess: async () => {
      setFeedbackSuccess('Courier profile successfully initialized.');
      setFeedbackError(null);
      await queryClient.invalidateQueries({ queryKey: profileKey });
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
      await queryClient.invalidateQueries({ queryKey: profileKey });
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

  const profile = profileQuery.data;
  const is404 =
    profileQuery.isError &&
    (profileQuery.error as { response?: { status?: number } })?.response?.status === 404;

  return (
    <main className="sd-page-container" aria-label="Courier Profile">
      {/* Page Header */}
      <div className="sd-page-header">
        <div className="sd-page-title-group">
          <h1 className="sd-page-title">Profile</h1>
          <p className="sd-page-subtitle">Your courier details used by dispatch.</p>
        </div>
      </div>

      {/* Success / Error Banners */}
      {feedbackSuccess && (
        <div className="sd-flash-notice" role="status" style={{ marginBottom: 20 }}>
          <span>{feedbackSuccess}</span>
          <Button
            type="button"
            className="sd-dismiss-btn"
            onClick={() => setFeedbackSuccess(null)}
          >
            ×
          </Button>
        </div>
      )}

      {feedbackError && (
        <div className="sd-flash-error" role="alert" style={{ marginBottom: 20 }}>
          <span>{feedbackError}</span>
          <Button
            type="button"
            className="sd-dismiss-btn"
            onClick={() => setFeedbackError(null)}
          >
            ×
          </Button>
        </div>
      )}

      {/* Loading State */}
      {profileQuery.isPending && (
        <div className="sd-card" style={{ padding: 40, textAlign: 'center' }}>
          <p className="sd-card-subtitle">Loading your courier profile…</p>
        </div>
      )}

      {/* Missing profile / 404 State */}
      {is404 && (
        <div className="sd-card" style={{ marginBottom: 24 }}>
          <h2 className="sd-card-title">No courier profile registered yet</h2>
          <p className="sd-card-subtitle" style={{ marginTop: 8 }}>
            Your user account has the Courier role, but a courier profile has not been initialized.
            Click the button below to provision your profile and connect to dispatch operations.
          </p>
          <div style={{ marginTop: 20 }}>
            <Button
              type="button"
              className="sd-btn-yellow"
              disabled={provisionMutation.isPending}
              onClick={() => provisionMutation.mutate()}
            >
              {provisionMutation.isPending ? 'Provisioning…' : 'Initialize Courier Profile'}
            </Button>
          </div>
        </div>
      )}

      {/* Form and Details Layout */}
      {(!profileQuery.isPending && !is404) && (
        <div className="sd-grid-2col sd-courier-grid">
          {/* Left Column: Your details Form */}
          <section className="sd-card" aria-labelledby="details-heading">
            <h2 id="details-heading" className="sd-card-title" style={{ marginBottom: 24 }}>
              Your details
            </h2>

            <form className="sd-form" onSubmit={handleSubmit} noValidate>
              {/* Form Validation Error Alert */}
              {formError && (
                <div className="sd-form-error-banner" role="alert" style={{ marginBottom: 16 }}>
                  <span>{formError}</span>
                </div>
              )}

              {/* Row 1: Name & Email */}
              <div className="sd-form-row-2col">
                <div className="sd-form-group">
                  <label htmlFor="courier-name-input" className="sd-input-label">
                    Name <span className="sr-only">Full Name</span>
                  </label>
                  <Input
                    id="courier-name-input"
                    className="sd-input"
                    type="text"
                    value={name}
                    aria-label="Full Name"
                    onChange={(e) => {
                      setName(e.target.value);
                      if (formError) setFormError(null);
                    }}
                    maxLength={200}
                    required
                  />
                </div>

                <div className="sd-form-group">
                  <label htmlFor="courier-email-input" className="sd-input-label">
                    Email <span className="sr-only">Account Email</span>
                  </label>
                  <Input
                    id="courier-email-input"
                    className="sd-input sd-input-disabled"
                    type="email"
                    value={email}
                    disabled
                    readOnly
                    aria-label="Account Email"
                    aria-readonly="true"
                  />
                </div>
              </div>

              {/* Row 2: Contact phone & Service zone */}
              <div className="sd-form-row-2col">
                <div className="sd-form-group">
                  <label htmlFor="courier-phone-input" className="sd-input-label">
                    Contact phone <span className="sr-only">Phone Number</span>
                  </label>
                  <Input
                    id="courier-phone-input"
                    className="sd-input"
                    type="tel"
                    value={phone}
                    aria-label="Phone Number"
                    onChange={(e) => setPhone(e.target.value)}
                    maxLength={32}
                  />
                </div>

                <div className="sd-form-group">
                  <label htmlFor="courier-zone-input" className="sd-input-label">
                    Service zone
                  </label>
                  <Input
                    id="courier-zone-input"
                    className="sd-input sd-input-disabled"
                    type="text"
                    value={zone}
                    disabled
                    readOnly
                  />
                </div>
              </div>

              {/* Row 3: Vehicle & Plate number */}
              <div className="sd-form-row-2col">
                <div className="sd-form-group">
                  <label htmlFor="courier-vehicle-select" className="sd-input-label">
                    Vehicle
                  </label>
                  <div className="sd-select-wrapper">
                    <Select
                      id="courier-vehicle-select"
                      className="sd-select"
                      value={vehicle}
                      onChange={(e) => setVehicle(e.target.value)}
                    >
                      <option value="Motorbike">Motorbike</option>
                      <option value="Van">Van</option>
                      <option value="Three-wheeler">Three-wheeler</option>
                      <option value="Bicycle">Bicycle</option>
                    </Select>
                  </div>
                </div>

                <div className="sd-form-group">
                  <label htmlFor="courier-plate-input" className="sd-input-label">
                    Plate number
                  </label>
                  <Input
                    id="courier-plate-input"
                    className="sd-input"
                    type="text"
                    value={plateNumber}
                    onChange={(e) => setPlateNumber(e.target.value)}
                  />
                </div>
              </div>

              {/* Submit Button */}
              <div style={{ marginTop: 28 }}>
                <Button
                  type="submit"
                  className="sd-btn-yellow"
                  aria-label="Save Personal Details"
                  disabled={updateMutation.isPending}
                >
                  {updateMutation.isPending ? 'Saving…' : 'Save changes'}
                </Button>
              </div>
            </form>
          </section>

          {/* Right Column: About these details */}
          <section className="sd-card" aria-labelledby="about-heading">
            <h2 id="about-heading" className="sd-card-title">About these details</h2>
            <p className="sd-card-subtitle" style={{ fontSize: 14, lineHeight: 1.6, marginTop: 12 }}>
              Name, email and service zone are managed by dispatch. You can update your phone number and vehicle details.
            </p>

            {/* Operational metadata & attributes */}
            <div className="sd-operational-meta-box">
              <div className="sd-meta-row">
                <span className="sd-meta-label">Eligibility Status</span>
                <span className="sd-meta-value">
                  {profile ? (profile.active ? 'Active & Approved' : 'Ineligible') : 'Active & Approved'}
                </span>
              </div>
              <div className="sd-meta-row">
                <span className="sd-meta-label">Dispatch Status</span>
                <span className="sd-meta-value">
                  {profile ? (profile.active ? 'Eligible for Dispatch' : 'Inactive') : 'Eligible for Dispatch'}
                </span>
              </div>
              <div className="sd-meta-row">
                <span className="sd-meta-label">Zone</span>
                <span className="sd-meta-value">
                  {profile?.assignedZone || 'Colombo Central'}
                </span>
              </div>
              <div className="sd-meta-row">
                <span className="sd-meta-label">Vehicle Category</span>
                <span className="sd-meta-value">
                  {profile?.vehicleType || 'MOTORCYCLE'}
                </span>
              </div>
              <div className="sd-meta-row">
                <span className="sd-meta-label">Courier ID</span>
                <code className="sd-meta-code">{profile?.id || 'cp-0000-1111-2222'}</code>
              </div>
            </div>
          </section>
        </div>
      )}
    </main>
  );
}
