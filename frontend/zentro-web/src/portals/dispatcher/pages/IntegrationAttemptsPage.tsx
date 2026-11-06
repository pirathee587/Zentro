import { useEffect, useState } from 'react';
import { friendlyApiError } from '@/shared/api/apiClient';
import { getIntegrationAttempts, type IntegrationAttempt } from '../api/integrationAttemptsApi';

function date(value: string) {
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? 'Date unavailable' : parsed.toLocaleString();
}

function AttemptTable({ rows, title }: { rows: IntegrationAttempt[]; title: string }) {
  return (
    <section className="staff-section">
      <div className="staff-section-heading">
        <div><h2>{title}</h2><p>Latest attempts and terminal outcomes. Payloads and secrets are never exposed.</p></div>
      </div>
      <div className="staff-panel">
        {!rows.length ? <div className="staff-empty">No attempts recorded.</div> : (
          <div className="staff-table-wrap">
            <table className="staff-table">
              <thead><tr><th>Event</th><th>Status</th><th>Attempts</th><th>Last error</th><th>Updated</th></tr></thead>
              <tbody>{rows.map((row) => (
                <tr key={row.id}>
                  <td><strong>{row.eventType}</strong><small>{row.trackingRef || row.parcelId}</small></td>
                  <td><span className={`staff-status staff-status-${row.status.toLowerCase()}`}>{row.status}</span></td>
                  <td>{row.attempts}</td>
                  <td>{row.lastError || '—'}</td>
                  <td>{date(row.updatedAt)}</td>
                </tr>
              ))}</tbody>
            </table>
          </div>
        )}
      </div>
    </section>
  );
}

export function IntegrationAttemptsPage() {
  const [data, setData] = useState<Awaited<ReturnType<typeof getIntegrationAttempts>> | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);

  const load = () => {
    setLoading(true);
    setError(null);
    void getIntegrationAttempts().then(setData).catch(setError).finally(() => setLoading(false));
  };

  useEffect(load, []);

  return (
    <main className="staff-main">
      <section className="staff-hero">
        <div><span className="staff-eyebrow">SWIFTDROP OPERATIONS</span><h1>Integration attempts</h1>
          <p>Monitor notification and merchant webhook delivery outcomes.</p></div>
        <button className="staff-review-button" type="button" onClick={load} disabled={loading}>Refresh</button>
      </section>
      {loading && <div className="staff-panel staff-empty" role="status">Loading integration history…</div>}
      {!loading && Boolean(error) && <div className="staff-panel staff-error" role="alert">
        {friendlyApiError(error, 'Could not load integration history. Please try again.')}
        <button className="staff-review-button" type="button" onClick={load}>Retry</button>
      </div>}
      {!loading && !error && data && <>
        <AttemptTable rows={data.notifications} title={`Notifications (${data.totalNotifications})`} />
        <AttemptTable rows={data.webhooks} title={`Webhooks (${data.totalWebhooks})`} />
      </>}
    </main>
  );
}
