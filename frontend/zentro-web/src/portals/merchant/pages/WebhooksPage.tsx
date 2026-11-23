import React, { useState } from 'react';
import { Button, Input } from '@/shared/ui';
import { friendlyApiError } from '@/shared/api/apiClient';
import { useMerchantWebhookQuery, useMerchantWebhookDeliveriesQuery, useRegisterMerchantWebhookMutation, useDisableMerchantWebhookMutation, useRotateMerchantWebhookSecretMutation } from '../hooks/useMerchantQueries';

export function WebhooksPage() {
  const config = useMerchantWebhookQuery(); const deliveries = useMerchantWebhookDeliveriesQuery();
  const register = useRegisterMerchantWebhookMutation(); const disable = useDisableMerchantWebhookMutation(); const rotate = useRotateMerchantWebhookSecretMutation();
  const [url, setUrl] = useState(''); const [secret, setSecret] = useState('');
  const submit = async (event: React.FormEvent) => { event.preventDefault(); try { const result = await register.mutateAsync(url); setSecret(result.secret); setUrl(''); } catch { /* mutation error rendered below */ } };
  const registered = config.data;
  return <main className="sd-page-container" aria-label="Merchant Webhooks">
    <div className="sd-page-header"><div className="sd-page-title-group"><h1 className="sd-page-title">Webhooks</h1><p className="sd-page-subtitle">Configure your endpoint and review delivery attempts.</p></div></div>
    <div className="sd-grid-2col" style={{ gap: 24, alignItems: 'start' }}>
      <section className="sd-card"><h2 className="sd-card-title">Configuration</h2>
        {config.isLoading && <p className="sd-cell-mute">Loading webhook configuration…</p>}
        {config.isError && (config.error as { response?: { status?: number } })?.response?.status !== 404 && <div role="alert"><p>{friendlyApiError(config.error, 'Could not load webhook configuration.')}</p><Button type="button" onClick={() => void config.refetch()}>Retry</Button></div>}
        {config.isError && (config.error as { response?: { status?: number } })?.response?.status === 404 && <p className="sd-cell-mute">No endpoint configured.</p>}
        {!config.isLoading && !config.isError && !registered && <p className="sd-cell-mute">No endpoint configured.</p>}
        {registered && <div className="sd-health-list"><div className="sd-health-row"><span className="sd-health-label">Endpoint</span><span className="sd-health-val-code">{registered.url}</span></div><div className="sd-health-row"><span className="sd-health-label">State</span><span className="sd-health-val">{registered.active ? 'Active' : 'Disabled'}</span></div><div className="sd-health-row"><span className="sd-health-label">Signing key</span><span className="sd-health-val-code">{registered.secretPrefix}••••</span></div></div>}
        <form onSubmit={submit} style={{ marginTop: 20 }}><label className="sd-input-label" htmlFor="webhook-url">Endpoint URL</label><Input id="webhook-url" type="url" required value={url} onChange={e => setUrl(e.target.value)} placeholder="https://example.com/webhooks"/><Button type="submit" disabled={register.isPending} style={{ marginTop: 12 }}>{registered ? 'Replace endpoint' : 'Register endpoint'}</Button></form>
        {(register.isError || disable.isError || rotate.isError) && <p role="alert">{friendlyApiError(register.error || disable.error || rotate.error, 'Webhook update failed.')}</p>}
        {secret && <p role="status" className="sd-cell-mute">Copy this signing secret now; it is shown only once: <code>{secret}</code></p>}
        {registered && <div style={{ display: 'flex', gap: 8, marginTop: 12 }}><Button type="button" disabled={rotate.isPending} onClick={async () => { try { setSecret((await rotate.mutateAsync()).secret); } catch { /* mutation error rendered above */ } }}>Rotate secret</Button><Button type="button" disabled={disable.isPending} onClick={() => disable.mutate()}>Disable endpoint</Button></div>}
      </section>
      <section className="sd-card"><h2 className="sd-card-title">Delivery history</h2>{deliveries.isLoading && <p className="sd-cell-mute">Loading delivery attempts…</p>}{deliveries.isError && <div role="alert"><p>{friendlyApiError(deliveries.error, 'Could not load delivery history.')}</p><Button type="button" onClick={() => void deliveries.refetch()}>Retry</Button></div>}{deliveries.isSuccess && deliveries.data.items.length === 0 && <p className="sd-cell-mute">No webhook delivery attempts yet.</p>}{deliveries.data?.items.length ? <div className="sd-table-wrap"><table className="sd-table"><thead><tr><th>Event</th><th>Status</th><th>Attempts</th><th>Last response</th><th>Created</th></tr></thead><tbody>{deliveries.data.items.map(item => <tr key={item.id}><td>{item.eventType}</td><td>{item.status}</td><td>{item.attempts}</td><td>{item.responseStatus ?? item.lastError ?? '—'}</td><td>{new Date(item.createdAt).toLocaleString()}</td></tr>)}</tbody></table></div> : null}<Button type="button" variant="secondary" onClick={() => void deliveries.refetch()} disabled={deliveries.isFetching}>Refresh history</Button></section>
    </div></main>;
}
