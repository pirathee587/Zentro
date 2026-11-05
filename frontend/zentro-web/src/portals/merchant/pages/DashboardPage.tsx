import { Link, useLocation } from 'react-router-dom';
import { useAuth } from '@/shared/auth/useAuth';
import { friendlyApiError } from '@/shared/api/apiClient';
import { Button, ErrorState, Spinner } from '@/shared/ui';
import { useMerchantApiKeyQuery, useMerchantApplicationQuery } from '../hooks/useMerchantQueries';

export function DashboardPage() {
  const { user } = useAuth();
  const location = useLocation();
  const navigationMerchantStatus = (location.state as { merchantStatus?: string } | null)?.merchantStatus;
  const pendingReview = user?.roles.includes('CUSTOMER') === true && (user.merchantStatus === 'PENDING' || navigationMerchantStatus === 'PENDING');
  const application = useMerchantApplicationQuery(!pendingReview);
  const key = useMerchantApiKeyQuery(!pendingReview);
  const firstName = user?.name?.trim().split(/\s+/)[0];

  if (pendingReview) return <main className="merchant-main merchant-dashboard">
    <section className="merchant-welcome"><div><span className="merchant-eyebrow">BUSINESS APPLICATION</span><h1>We're reviewing your details.</h1><p>Your merchant application is in the review queue. You can keep this dashboard open and check again later.</p></div><span className="merchant-status-pill is-pending"><i /> Pending review</span></section>
    <section className="merchant-setup-card"><div><span className="merchant-eyebrow">WHAT HAPPENS NEXT</span><h2>Your business access is being reviewed</h2><p>Once a dispatcher updates your application, sign in again to use the merchant tools. Your customer account remains available while you wait.</p></div><Button variant="secondary" className="merchant-outline-link" type="button" onClick={() => window.location.reload()}>Refresh status</Button></section>
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
