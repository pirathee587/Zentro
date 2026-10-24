import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useLocation } from 'react-router-dom';
import { apiClient, friendlyApiError } from '@/shared/api/apiClient';
import { useAuth } from '@/shared/auth/useAuth';
import { Button, ErrorState, Spinner } from '@/shared/ui';

type MerchantApplication = {
  businessName: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED';
  createdAt: string;
  reviewedAt?: string | null;
};

type ApiKeyMetadata = {
  id: string;
  prefix: string;
  createdAt: string;
  rotatedAt: string;
  revokedAt?: string | null;
  active: boolean;
};

type IssuedApiKey = { secret: string; credential: ApiKeyMetadata };

function useMerchantRequest<T>(path: string, method: 'get' | 'post' | 'delete' = 'get', enabled = true) {
  const { user, refreshAccessToken } = useAuth();
  return useQuery<T>({
    queryKey: ['merchant-api', path],
    enabled: Boolean(user?.accessToken) && enabled,
    retry: false,
    queryFn: async () => {
      const request = async (force = false) => {
        const accessToken = refreshAccessToken ? await refreshAccessToken(force) : user?.accessToken;
        if (!accessToken) throw new Error('Your sign-in session has ended. Please sign in again.');
        return apiClient.request<T>({ url: path, method, headers: { Authorization: `Bearer ${accessToken}` } });
      };
      try {
        return (await request()).data;
      } catch (error) {
        if ((error as { response?: { status?: number } })?.response?.status !== 401 || !refreshAccessToken) throw error;
        return (await request(true)).data;
      }
    },
  });
}

function useMerchantApplication(enabled = true) {
  return useMerchantRequest<MerchantApplication>('/api/v1/merchants/me', 'get', enabled);
}

export const RegisterPage = () => <main className="merchant-main"><section className="merchant-card"><span className="merchant-eyebrow">BUSINESS REGISTRATION</span><h1>Start delivering with Zentro.</h1><p>Submit your business information from the registration page to request merchant access.</p><Link className="merchant-primary-link" to="/merchant/register-business">Continue registration</Link></section></main>;

export function DashboardPage() {
  const { user } = useAuth();
  const location = useLocation();
  const navigationMerchantStatus = (location.state as { merchantStatus?: string } | null)?.merchantStatus;
  const pendingReview = user?.roles.includes('CUSTOMER') === true && (user.merchantStatus === 'PENDING' || navigationMerchantStatus === 'PENDING');
  const application = useMerchantApplication(!pendingReview);
  const key = useMerchantRequest<ApiKeyMetadata | null>('/api/v1/merchants/api-key', 'get', !pendingReview);
  const firstName = user?.name?.trim().split(/\s+/)[0];

  if (pendingReview) return <main className="merchant-main merchant-dashboard">
    <section className="merchant-welcome"><div><span className="merchant-eyebrow">BUSINESS APPLICATION</span><h1>We're reviewing your details.</h1><p>Your merchant application is in the review queue. You can keep this dashboard open and check again later.</p></div><span className="merchant-status-pill is-pending"><i /> Pending review</span></section>
    <section className="merchant-setup-card"><div><span className="merchant-eyebrow">WHAT HAPPENS NEXT</span><h2>Your business access is being reviewed</h2><p>Once a dispatcher updates your application, sign in again to use the merchant tools. Your customer account remains available while you wait.</p></div><button className="merchant-outline-link" type="button" onClick={() => window.location.reload()}>Refresh status</button></section>
  </main>;

  if (application.isLoading) return <main className="merchant-main"><div className="merchant-loading"><Spinner label="Loading your business dashboard" /></div></main>;
  if (application.isError) return <main className="merchant-main"><ErrorState message={friendlyApiError(application.error, 'We could not load your business details.')} /></main>;
  const details = application.data;
  const activeKey = key.data?.active === true;

  return <main className="merchant-main merchant-dashboard">
    <section className="merchant-welcome">
      <div><span className="merchant-eyebrow">MERCHANT OVERVIEW</span><h1>Good to see you{firstName ? `, ${firstName}` : ''}.</h1><p>{details?.businessName || 'Your business'} is connected to Zentro. Keep delivery integrations and account security in one place.</p></div>
      <span className="merchant-status-pill"><i /> Account approved</span>
    </section>

    <div className="merchant-overview-grid">
      <article className="merchant-overview-card"><span className="merchant-card-icon" aria-hidden="true">✓</span><div><small>BUSINESS STATUS</small><strong>Approved</strong><p>{details?.businessName || 'Merchant account'}</p></div></article>
      <article className="merchant-overview-card"><span className="merchant-card-icon merchant-card-icon-gold" aria-hidden="true">⌘</span><div><small>API CREDENTIAL</small><strong>{key.isLoading ? 'Checking…' : activeKey ? 'Active key' : 'Not set up'}</strong><p>{activeKey ? `Key prefix ${key.data?.prefix}` : 'Create a key to prepare your integration.'}</p></div></article>
    </div>

    {key.isError && <ErrorState message={friendlyApiError(key.error, 'We could not load API key status.')} />}
    <div className="merchant-section-heading"><div><span className="merchant-eyebrow">YOUR WORKSPACE</span><h2>Business tools</h2></div></div>
    <div className="merchant-tools merchant-tool-grid">
      <Link className="merchant-tool merchant-tool-featured" to="/merchant/dashboard/api-credentials"><span className="merchant-tool-symbol" aria-hidden="true">⌘</span><strong>API credentials</strong><span>Create, rotate, or revoke the secret key for your business integration.</span><b>{activeKey ? 'Manage API key' : 'Set up API key'} <span aria-hidden="true">→</span></b></Link>
      <Link className="merchant-tool" to="/merchant/dashboard/webhooks"><span className="merchant-tool-symbol merchant-tool-symbol-light" aria-hidden="true">↗</span><strong>Webhooks</strong><span>Learn how delivery updates can be sent to your own system.</span><b>View integration guide <span aria-hidden="true">→</span></b></Link>
    </div>

    <section className="merchant-setup-card"><div><span className="merchant-eyebrow">NEXT STEP</span><h2>Connect your system</h2><p>Create an API key, store it in your server environment, and never expose it in a browser app or source code.</p></div><Link className="merchant-outline-link" to="/merchant/dashboard/api-credentials">Open API credentials <span aria-hidden="true">→</span></Link></section>
  </main>;
}

export function ApiCredentialsPage() {
  const queryClient = useQueryClient();
  const { user, refreshAccessToken } = useAuth();
  const key = useMerchantRequest<ApiKeyMetadata | null>('/api/v1/merchants/api-key');
  const [secret, setSecret] = useState('');
  const [copied, setCopied] = useState(false);
  const [localError, setLocalError] = useState('');

  const authorizedRequest = async <T,>(method: 'post' | 'delete', force = false) => {
    const accessToken = refreshAccessToken ? await refreshAccessToken(force) : user?.accessToken;
    if (!accessToken) throw new Error('Your sign-in session has ended. Please sign in again.');
    return apiClient.request<T>({ url: '/api/v1/merchants/api-key', method, headers: { Authorization: `Bearer ${accessToken}` } });
  };

  const issue = useMutation({
    mutationFn: async () => {
      try { return (await authorizedRequest<IssuedApiKey>('post')).data; }
      catch (error) { if ((error as { response?: { status?: number } })?.response?.status !== 401 || !refreshAccessToken) throw error; return (await authorizedRequest<IssuedApiKey>('post', true)).data; }
    },
    onSuccess: async (result) => { setSecret(result.secret); setCopied(false); setLocalError(''); await queryClient.invalidateQueries({ queryKey: ['merchant-api'] }); },
  });
  const revoke = useMutation({
    mutationFn: async () => {
      try { return (await authorizedRequest<ApiKeyMetadata | null>('delete')).data; }
      catch (error) { if ((error as { response?: { status?: number } })?.response?.status !== 401 || !refreshAccessToken) throw error; return (await authorizedRequest<ApiKeyMetadata | null>('delete', true)).data; }
    },
    onSuccess: async () => { setSecret(''); setLocalError(''); await queryClient.invalidateQueries({ queryKey: ['merchant-api'] }); },
  });

  const activeKey = key.data?.active === true;
  const actionError = issue.error || revoke.error;

  return <main className="merchant-main merchant-credentials">
    <Link className="merchant-back-link" to="/merchant/dashboard">← Back to overview</Link>
    <section className="merchant-page-heading"><span className="merchant-eyebrow">DEVELOPER SETTINGS</span><h1>API credentials</h1><p>Manage the private key associated with your approved Zentro business.</p></section>
    {key.isLoading && <div className="merchant-loading"><Spinner label="Loading API key status" /></div>}
    {key.isError && <ErrorState message={friendlyApiError(key.error, 'We could not load your API key.')} />}
    {!key.isLoading && !key.isError && <section className="merchant-key-card">
      <div className="merchant-key-card-heading"><div><span className="merchant-eyebrow">LIVE CREDENTIAL</span><h2>{activeKey ? 'Your API key' : 'No API key yet'}</h2></div><span className={`merchant-key-state${activeKey ? ' is-active' : ''}`}><i />{activeKey ? 'Active' : 'Not created'}</span></div>
      {activeKey ? <><div className="merchant-key-preview"><code>{key.data?.prefix}{'•'.repeat(20)}</code><span>Only the prefix is stored and shown here.</span></div><dl className="merchant-key-details"><div><dt>Created</dt><dd>{key.data?.createdAt ? new Date(key.data.createdAt).toLocaleDateString() : '—'}</dd></div><div><dt>Last rotated</dt><dd>{key.data?.rotatedAt ? new Date(key.data.rotatedAt).toLocaleDateString() : 'Not rotated'}</dd></div></dl></> : <p className="merchant-muted-copy">Generate a secret key for your business integration. The complete key will be shown once after it is created.</p>}
      {secret && <div className="merchant-secret-panel" role="status"><strong>Copy and store this key now</strong><p>For security, Zentro will not show the full key again. Rotating it invalidates the previous key immediately.</p><code>{secret}</code><Button type="button" variant="secondary" onClick={() => { void navigator.clipboard.writeText(secret).then(() => setCopied(true)).catch(() => setLocalError('Clipboard access failed. Select and copy the key manually.')); }}>{copied ? 'Copied' : 'Copy key'}</Button></div>}
      {localError && <ErrorState message={localError} />}
      {actionError && <ErrorState message={friendlyApiError(actionError, 'We could not update the API key.')} />}
      <div className="merchant-key-actions"><Button disabled={key.isLoading || issue.isPending || revoke.isPending} onClick={() => { if (activeKey && !window.confirm('Rotate this key? The current key will stop working immediately.')) return; setSecret(''); issue.mutate(); }}>{issue.isPending ? <Spinner label="Creating key" /> : activeKey ? 'Rotate API key' : 'Generate API key'}</Button>{activeKey && <Button type="button" variant="secondary" disabled={issue.isPending || revoke.isPending} onClick={() => { if (!window.confirm('Revoke this API key? Any integration using it will stop working immediately.')) return; setSecret(''); revoke.mutate(); }}>{revoke.isPending ? 'Revoking…' : 'Revoke key'}</Button>}</div>
    </section>}
    <aside className="merchant-security-note"><span aria-hidden="true">i</span><p><strong>Keep your key private.</strong> Store it in your backend environment variables. Never put it in frontend code, public repositories, or screenshots.</p></aside>
  </main>;
}

export function WebhooksPage() {
  return <main className="merchant-main merchant-credentials"><Link className="merchant-back-link" to="/merchant/dashboard">← Back to overview</Link><section className="merchant-page-heading"><span className="merchant-eyebrow">DEVELOPER SETTINGS</span><h1>Webhooks</h1><p>Receive delivery status events in your own system.</p></section><section className="merchant-key-card merchant-coming-soon"><span className="merchant-eyebrow">INTEGRATION GUIDE</span><h2>Webhook delivery is not enabled yet</h2><p>The current backend does not include webhook endpoint registration or event delivery. You can manage your API key now; webhook configuration will appear here when that service is available.</p></section></main>;
}
