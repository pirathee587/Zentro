import { Link } from 'react-router-dom';

export function WebhooksPage() {
  return <main className="merchant-main merchant-credentials"><Link className="merchant-back-link" to="/merchant/dashboard">← Back to overview</Link><section className="merchant-page-heading"><span className="merchant-eyebrow">DEVELOPER SETTINGS</span><h1>Webhooks</h1><p>Receive delivery status events in your own system.</p></section><section className="merchant-key-card merchant-coming-soon"><span className="merchant-eyebrow">INTEGRATION GUIDE</span><h2>Webhook delivery is not enabled yet</h2><p>The current backend does not include webhook endpoint registration or event delivery. You can manage your API key now; webhook configuration will appear here when that service is available.</p></section></main>;
}
