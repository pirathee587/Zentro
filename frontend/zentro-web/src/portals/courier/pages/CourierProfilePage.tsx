import { useRef, useState, type FormEvent } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { RefreshCw, UserRound } from 'lucide-react';
import { useAuth } from '@/shared/auth/useAuth';
import { friendlyApiError } from '@/shared/api/apiClient';
import { Button, Input } from '@/shared/ui';
import { principalCacheScope, queryKeys } from '@/shared/api/queryKeys';
import {
  getCourierProfile,
  provisionCourierProfile,
  updateCourierPersonalInfo,
  type CourierProfile,
  type UpdateCourierPersonalRequest,
} from '../api/courierApi';
import { CourierPage } from './CourierPageParts';

export function CourierProfilePage() {
  const { user } = useAuth();
  return <ProfileAccount key={principalCacheScope(user?.id)} />;
}
function ProfileAccount() {
  const { user, login } = useAuth();
  const client = useQueryClient();
  const key = queryKeys.courier.profile(principalCacheScope(user?.id));
  const [notice, setNotice] = useState('');
  const [revision, setRevision] = useState(0);
  const profile = useQuery({
    queryKey: key,
    enabled: Boolean(user?.id && user.accessToken),
    gcTime: 0,
    staleTime: 10_000,
    refetchOnWindowFocus: false,
    retry: false,
    queryFn: ({ signal }) => getCourierProfile(user!.accessToken, signal),
  });
  const initialize = useMutation({
    mutationFn: () => provisionCourierProfile(user!.accessToken),
    onSuccess: async (data) => {
      client.setQueryData(key, data);
      setNotice('Courier profile successfully initialized.');
    },
    retry: false,
  });
  const status = (profile.error as { response?: { status?: number } })?.response?.status;
  return (
    <CourierPage
      title="Profile"
      description="Your contact information and dispatcher-managed courier settings."
      action={
        user?.accessToken && (
          <Button
            disabled={profile.isFetching || initialize.isPending}
            onClick={async () => {
              setNotice('');
              const result = await profile.refetch();
              if (result.isSuccess) setRevision((value) => value + 1);
            }}
          >
            <RefreshCw size={16} aria-hidden="true" /> Reload saved profile
          </Button>
        )
      }
    >
      {!user?.accessToken ? (
        <p role="status">Sign in to view your courier profile.</p>
      ) : profile.isPending ? (
        <section className="cs-card cs-state" role="status">
          <UserRound aria-hidden="true" />
          <p>Loading your courier profile...</p>
        </section>
      ) : profile.isError ? (
        status === 404 ? (
          <section className="cs-card">
            <h2>No courier profile registered yet</h2>
            <p>Initialize your profile to connect your existing courier account to dispatch.</p>
            <Button disabled={initialize.isPending} onClick={() => initialize.mutate()}>
              {initialize.isPending ? 'Initializing...' : 'Initialize Courier Profile'}
            </Button>
            {initialize.isError && (
              <p role="alert">
                {friendlyApiError(
                  initialize.error,
                  'Could not initialize your profile. Please try again.',
                )}
              </p>
            )}
          </section>
        ) : (
          <section className="cs-card" role="alert">
            <h2>Profile unavailable</h2>
            <p>
              {friendlyApiError(
                profile.error,
                'Could not load your courier profile. Please try again.',
              )}
            </p>
            {status === 401 ? (
              <Button onClick={() => void login('/courier/profile', undefined, 'staff')}>
                Sign in again
              </Button>
            ) : (
              <Button onClick={() => void profile.refetch()}>Try again</Button>
            )}
          </section>
        )
      ) : profile.data ? (
        <>
          {notice && (
            <p className="cs-notice" role="status">
              {notice}
            </p>
          )}
          <PersonalDetails key={revision} profile={profile.data} token={user.accessToken} />
        </>
      ) : (
        <p role="alert">Your profile could not be loaded. Refresh to try again.</p>
      )}
    </CourierPage>
  );
}
function PersonalDetails({ profile, token }: { profile: CourierProfile; token: string }) {
  const [name, setName] = useState(profile.name ?? '');
  const [phone, setPhone] = useState(profile.phone ?? '');
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const nameInput = useRef<HTMLInputElement>(null);
  const phoneInput = useRef<HTMLInputElement>(null);
  const { user } = useAuth();
  const client = useQueryClient();
  const profileKey = queryKeys.courier.profile(principalCacheScope(user?.id));
  const update = useMutation({
    mutationFn: (body: UpdateCourierPersonalRequest) => updateCourierPersonalInfo(body, token),
    retry: false,
    onSuccess: async (_result, submitted) => {
      client.setQueryData<CourierProfile>(profileKey, (current) =>
        current
          ? {
              ...current,
              name: submitted.name ?? current.name,
              phone: submitted.phone ?? current.phone,
            }
          : current,
      );
      setName((value) => value.trim());
      setPhone((value) => value.trim());
      setSuccess('Your personal details have been updated successfully.');
      await client.invalidateQueries({ queryKey: profileKey, refetchType: 'none' });
    },
  });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    setError('');
    setSuccess('');
    update.reset();
    const cleanName = name.trim(),
      cleanPhone = phone.trim();
    if (!cleanName) {
      setError('Display name cannot be empty.');
      nameInput.current?.focus();
      return;
    }
    if (cleanName.length > 200) {
      setError('Display name must not exceed 200 characters.');
      nameInput.current?.focus();
      return;
    }
    if (cleanPhone.length > 32) {
      setError('Phone number must not exceed 32 characters.');
      phoneInput.current?.focus();
      return;
    }
    if (profile.phone && !cleanPhone) {
      setError('Phone number cannot be cleared through this form.');
      phoneInput.current?.focus();
      return;
    }
    update.mutate({ name: cleanName, ...(cleanPhone ? { phone: cleanPhone } : {}) });
  };
  const active = profile.active && profile.accountStatus === 'ACTIVE';
  return (
    <div className="cs-grid">
      <section className="cs-card" aria-labelledby="personal-details">
        <h2 id="personal-details">Your details</h2>
        <form onSubmit={submit} noValidate>
          {error && (
            <p id="profile-validation" role="alert">
              {error}
            </p>
          )}
          {update.isError && (
            <p role="alert">
              {friendlyApiError(update.error, 'Could not save profile changes. Please try again.')}
            </p>
          )}
          {success && <p role="status">{success}</p>}
          <div className="cs-filter">
            <label htmlFor="courier-name-input">
              Full Name
              <input
                id="courier-name-input"
                ref={nameInput}
                value={name}
                maxLength={200}
                autoComplete="name"
                aria-describedby={error ? 'profile-validation' : undefined}
                onChange={(e) => {
                  setName(e.target.value);
                  setError('');
                  setSuccess('');
                }}
              />
            </label>
            <label htmlFor="courier-phone-input">
              Phone Number
              <input
                id="courier-phone-input"
                ref={phoneInput}
                type="tel"
                value={phone}
                maxLength={32}
                autoComplete="tel"
                onChange={(e) => {
                  setPhone(e.target.value);
                  setError('');
                  setSuccess('');
                }}
              />
            </label>
          </div>
          <label htmlFor="courier-email-input">
            Account Email
            <Input
              id="courier-email-input"
              value={profile.email ?? ''}
              disabled
              readOnly
              placeholder="Not provided"
            />
          </label>
          <p className="cs-muted">
            Only your display name and phone number can be edited here. Email is managed by your
            account provider.
          </p>
          <Button
            type="submit"
            aria-label="Save Personal Details"
            disabled={update.isPending || profile.accountStatus !== 'ACTIVE'}
          >
            {update.isPending ? 'Saving...' : 'Save changes'}
          </Button>
        </form>
      </section>
      <section className="cs-card" aria-labelledby="courier-settings">
        <h2 id="courier-settings">Courier settings</h2>
        <span className="cs-badge">{active ? 'Profile active' : 'Profile inactive'}</span>
        <dl>
          <dt>Account status</dt>
          <dd>{profile.accountStatus || 'Unavailable'}</dd>
          <dt>Zone</dt>
          <dd>{profile.assignedZone || 'Not assigned'}</dd>
          <dt>Vehicle category</dt>
          <dd>{profile.vehicleType || 'Not assigned'}</dd>
        </dl>
        <p className="cs-muted">
          Dispatch manages activation, service zone and vehicle category. Eligibility for each
          assignment also depends on destination and workload.
        </p>
        {profile.accountStatus !== 'ACTIVE' && (
          <p role="status">Contact dispatch before editing an inactive account.</p>
        )}
      </section>
    </div>
  );
}
