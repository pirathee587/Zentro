import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { friendlyApiError } from '@/shared/api/apiClient';
import { Button, ErrorState, Spinner } from '@/shared/ui';
import { useMerchantApiKeyQuery, useIssueMerchantApiKeyMutation, useRevokeMerchantApiKeyMutation } from '../hooks/useMerchantQueries';

export function ApiCredentialsPage() {
  const key = useMerchantApiKeyQuery();
  const [secret, setSecret] = useState('');
  const [copied, setCopied] = useState(false);
  const [localError, setLocalError] = useState('');
  const secretHeading = useRef<HTMLElement>(null);
  useEffect(() => { if (secret) secretHeading.current?.focus(); }, [secret]);

  const issue = useIssueMerchantApiKeyMutation();
  const revoke = useRevokeMerchantApiKeyMutation();

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
      {secret && <div className="merchant-secret-panel"><strong ref={secretHeading} tabIndex={-1}>Copy and store this key now</strong><p>For security, Zentro will not show the full key again. Rotating it invalidates the previous key immediately.</p><code>{secret}</code><Button type="button" variant="secondary" onClick={() => { void navigator.clipboard.writeText(secret).then(() => setCopied(true)).catch(() => setLocalError('Clipboard access failed. Select and copy the key manually.')); }}>{copied ? 'Copied' : 'Copy key'}</Button><span role="status">{copied ? 'Key copied.' : ''}</span></div>}
      {localError && <ErrorState message={localError} />}
      {actionError && <ErrorState message={friendlyApiError(actionError, 'We could not update the API key.')} />}
      <div className="merchant-key-actions"><Button disabled={key.isLoading || issue.isPending || revoke.isPending} onClick={() => { if (activeKey && !window.confirm('Rotate this key? The current key will stop working immediately.')) return; setSecret(''); issue.mutate(undefined, { onSuccess: (result) => { setSecret(result.secret); setCopied(false); setLocalError(''); } }); }}>{issue.isPending ? <Spinner label="Creating key" /> : activeKey ? 'Rotate API key' : 'Generate API key'}</Button>{activeKey && <Button type="button" variant="secondary" disabled={issue.isPending || revoke.isPending} onClick={() => { if (!window.confirm('Revoke this API key? Any integration using it will stop working immediately.')) return; setSecret(''); revoke.mutate(undefined, { onSuccess: () => { setSecret(''); setLocalError(''); } }); }}>{revoke.isPending ? 'Revoking…' : 'Revoke key'}</Button>}</div>
    </section>}
    <aside className="merchant-security-note"><span aria-hidden="true">i</span><p><strong>Keep your key private.</strong> Store it in your backend environment variables. Never put it in frontend code, public repositories, or screenshots.</p></aside>
  </main>;
}
