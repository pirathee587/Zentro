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
  const { user, refreshAccessToken } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const application = useMerchantApplicationQuery();
  const [form, setForm] = useState({ businessName: '', businessRegNo: '', contactPhone: '' });
  const [formError, setFormError] = useState('');
  const [forcedPending, setForcedPending] = useState(false);
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

  useEffect(() => {
    if (application.data && location.pathname !== '/merchant/status') {
      navigate('/merchant/status', { replace: true });
    }
  }, [application.data, location.pathname, navigate]);

  const status = forcedPending ? 'PENDING' : application.data?.status;
  const isNotFound = responseStatus(application.error) === 404;

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
    return <section className="auth-card"><Spinner /><p>Checking your business status...</p></section>;
  }

  if (application.isError && !isNotFound) {
    return <section className="auth-card"><ErrorState message={friendlyApiError(application.error, 'We could not load your business status. Please try again.')} /></section>;
  }

  if (status === 'APPROVED') {
    return <section className="auth-card">
      <h1>Your business is approved.</h1>
      <p>A dispatcher will assign merchant access to your account. Sign in again after access is enabled.</p>
      <EmptyState message="Merchant access requires the MERCHANT role." />
    </section>;
  }

  if (status === 'PENDING') {
    return <section className="auth-card">
      <span className="eyebrow">APPLICATION STATUS</span>
      <h1>Your application is <em>PENDING.</em></h1>
      <p>Your business profile is pending review. We'll update this page when a decision is made.</p>
      <Button variant="secondary" onClick={() => { setForcedPending(false); void application.refetch(); }}>Refresh status</Button>
    </section>;
  }

  if (status === 'REJECTED') {
    return <section className="auth-card">
      <h1>A little more information, please.</h1>
      <p>{application.data?.rejectionReason || 'Your application was not approved. Please contact our team for next steps.'}</p>
      <EmptyState message="Only a dispatcher can approve merchant access." />
    </section>;
  }

  return <form className="auth-card" onSubmit={submit}>
    <span className="eyebrow">BUSINESS REGISTRATION</span>
    <h1>Let's get<br /><em>good things moving.</em></h1>
    <label>Business name<Input required maxLength={140} value={form.businessName} onChange={(event) => setForm({ ...form, businessName: event.target.value })} /></label>
    <label>Business registration number<Input maxLength={80} value={form.businessRegNo} onChange={(event) => setForm({ ...form, businessRegNo: event.target.value })} /></label>
    <label>Contact phone<Input required type="tel" pattern="\+?[0-9]{9,15}" aria-describedby="contact-phone-help" value={form.contactPhone} onChange={(event) => setForm({ ...form, contactPhone: event.target.value })} /><small id="contact-phone-help">Use 9 to 15 digits, with an optional + prefix.</small></label>
    {formError && <ErrorState message={formError} />}
    {createApplication.isError && responseStatus(createApplication.error) !== 409 && <ErrorState message={friendlyApiError(createApplication.error, 'We could not submit your business details. Please try again.')} />}
    <Button className="auth-submit" disabled={createApplication.isPending}>{createApplication.isPending ? <Spinner label="Submitting business details" /> : 'Submit for review'}</Button>
  </form>;
}
