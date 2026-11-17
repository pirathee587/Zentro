import { useEffect, useState, type FormEvent } from 'react';
import axios from 'axios';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '@/shared/auth/useAuth';
import { friendlyApiError } from '@/shared/api/apiClient';
import { Button, EmptyState, ErrorState, Input, Spinner } from '@/shared/ui';
import {
  createMerchantApplication,
  type CreateMerchantApplicationRequest,
} from '../api/merchantApi';
import { merchantQueryKeys, useMerchantApplicationQuery } from '../hooks/useMerchantQueries';

function responseStatus(error: unknown): number | undefined {
  if (axios.isAxiosError(error)) return error.response?.status;
  if (typeof error !== 'object' || error === null || !('response' in error)) return undefined;
  const response = error.response;
  if (typeof response !== 'object' || response === null || !('status' in response)) return undefined;
  return typeof response.status === 'number' ? response.status : undefined;
}

export function MerchantApplicationPage() {
  const { user, login, refreshAccessToken } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const application = useMerchantApplicationQuery();
  const [form, setForm] = useState({ businessName: '', businessRegNo: '', contactPhone: '' });
  const [formError, setFormError] = useState('');
  const [forcedPending, setForcedPending] = useState(false);
  const [attemptedRoleRefresh, setAttemptedRoleRefresh] = useState(false);
  const auth = { accessToken: user?.accessToken, refreshAccessToken };

  const createApplication = useMutation({
    mutationFn: (request: CreateMerchantApplicationRequest) => createMerchantApplication(auth, request),
    onSuccess: async (result) => {
      setForcedPending(false);
      queryClient.setQueryData(merchantQueryKeys.application(user?.id), result);
      await queryClient.invalidateQueries({ queryKey: ['current-user', user?.id] });
      navigate('/merchant/status', { replace: true });
    },
  });

  const status = forcedPending ? 'PENDING' : application.data?.status;
  const isNotFound = responseStatus(application.error) === 404;

  useEffect(() => {
    if (application.data && location.pathname !== '/merchant/status') {
      navigate('/merchant/status', { replace: true });
    }
  }, [application.data, location.pathname, navigate]);

  useEffect(() => {
    if (status === 'APPROVED' || user?.merchantStatus === 'APPROVED') {
      navigate('/merchant/dashboard', { replace: true });
    }
  }, [status, user?.merchantStatus, navigate]);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const contactPhone = form.contactPhone.replace(/[\s()-]/g, '');
    if (!/^\+?[0-9]{9,15}$/.test(contactPhone)) {
      setFormError('Enter a phone number with 9 to 15 digits.');
      return;
    }

    setFormError('');
    createApplication.mutate({
      businessName: form.businessName.trim(),
      businessRegNo: form.businessRegNo.trim(),
      contactPhone,
    }, {
      onError: async (error) => {
        if (responseStatus(error) === 409) {
          setForcedPending(true);
          await queryClient.invalidateQueries({ queryKey: merchantQueryKeys.application(user?.id) });
          navigate('/merchant/status', { replace: true });
        }
      },
    });
  }

  if (application.isLoading) {
    return <section className="auth-card merchant-application-card"><Spinner /><p>Checking your business status...</p></section>;
  }

  if (application.isError && !isNotFound) {
    return <section className="auth-card merchant-application-card"><ErrorState message={friendlyApiError(application.error, 'We could not load your business status. Please try again.')} /></section>;
  }

  if (status === 'APPROVED' || user?.merchantStatus === 'APPROVED') {
    return <section className="auth-card merchant-application-card">
      <span className="eyebrow" style={{ color: '#0e6245' }}>APPLICATION APPROVED</span>
      <h1>Your business is approved!</h1>
      <p>Your merchant profile has been reviewed and approved. Enter your merchant dashboard to manage shipments, track deliveries, and issue API keys.</p>
      <Button
        className="auth-submit"
        onClick={() => {
          navigate('/merchant/dashboard', { replace: true });
        }}
      >
        Open Merchant Dashboard
      </Button>
    </section>;
  }

  if (status === 'PENDING') {
    return <section className="auth-card merchant-application-card">
      <span className="eyebrow">APPLICATION STATUS</span>
      <h1>Your application is <em>PENDING.</em></h1>
      <p>Your business profile is pending review. We'll update this page when a decision is made.</p>
      <Button
        variant="secondary"
        onClick={async () => {
          setForcedPending(false);
          const res = await application.refetch();
          if (refreshAccessToken) {
            await refreshAccessToken(true).catch(() => undefined);
          }
          await queryClient.invalidateQueries({ queryKey: ['current-user', user?.id] });
          if (res.data?.status === 'APPROVED' || user?.merchantStatus === 'APPROVED') {
            navigate('/merchant/dashboard', { replace: true });
          }
        }}
      >
        Refresh status
      </Button>
    </section>;
  }

  if (status === 'REJECTED') {
    return <section className="auth-card merchant-application-card">
      <h1>A little more information, please.</h1>
      <p>{application.data?.rejectionReason || 'Your application was not approved. Please contact our team for next steps.'}</p>
      <EmptyState message="Only a dispatcher can approve merchant access." />
    </section>;
  }

  return <form className="auth-card auth-form merchant-application-card" onSubmit={submit}>
    <span className="eyebrow">BUSINESS REGISTRATION</span>
    <h1>Let's get<br /><em>good things moving.</em></h1>
    <label htmlFor="business-name">Business name
      <Input id="business-name" required maxLength={140} value={form.businessName} onChange={(event) => setForm({ ...form, businessName: event.target.value })} placeholder="Your company or shop name" />
    </label>
    <label htmlFor="business-reg-no">Business registration number
      <Input id="business-reg-no" maxLength={80} value={form.businessRegNo} onChange={(event) => setForm({ ...form, businessRegNo: event.target.value })} placeholder="e.g. PV-123456" />
    </label>
    <label htmlFor="contact-phone">Contact phone
      <Input id="contact-phone" required type="tel" aria-describedby="contact-phone-help" value={form.contactPhone} onChange={(event) => setForm({ ...form, contactPhone: event.target.value })} placeholder="07XXXXXXXX or +947XXXXXXXX" />
    </label>
    <small id="contact-phone-help" className="auth-hint">Use 9 to 15 digits, with an optional + prefix.</small>
    {formError && <ErrorState message={formError} />}
    {createApplication.isError && responseStatus(createApplication.error) !== 409 && <ErrorState message={friendlyApiError(createApplication.error, 'We could not submit your business details. Please try again.')} />}
    <Button className="auth-submit" disabled={createApplication.isPending}>{createApplication.isPending ? <Spinner label="Submitting business details" /> : 'Submit for review'}</Button>
  </form>;
}
