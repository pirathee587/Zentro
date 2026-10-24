import { Link } from 'react-router-dom';
import { useAuth } from '@/shared/auth/useAuth';
import { PortalPlaceholder } from '@/shared/ui';

function PackageIcon() {
  return <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 3 8 4.5v9L12 21l-8-4.5v-9L12 3Z" /><path d="m4.3 7.6 7.7 4.3 7.7-4.3M12 12v9M8 5.2l8 4.5" /></svg>;
}

function RouteIcon() {
  return <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 20a2 2 0 1 0 0-4 2 2 0 0 0 0 4ZM18 8a2 2 0 1 0 0-4 2 2 0 0 0 0 4Z" /><path d="M8 18h5a3 3 0 0 0 3-3V9M16 6H9a3 3 0 0 0-3 3v4" /></svg>;
}

function ArrowIcon() {
  return <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12h14m-6-6 6 6-6 6" /></svg>;
}

const actions = [
  { to: '/customer/create-delivery', eyebrow: 'SEND', title: 'Send a parcel', description: 'Arrange a thoughtful delivery, from pickup to doorstep.', icon: <PackageIcon /> },
  { to: '/track', eyebrow: 'FOLLOW', title: 'Track a parcel', description: 'Check the latest update using your tracking number.', icon: <RouteIcon /> },
  { to: '/customer/parcels', eyebrow: 'YOUR ACTIVITY', title: 'My parcels', description: 'Open your delivery space and keep everything together.', icon: <PackageIcon /> },
];

export function CustomerHomePage() {
  const { user } = useAuth();
  const firstName = user?.name?.trim().split(/\s+/)[0];

  return (
    <main className="customer-main">
      <section className="customer-welcome">
        <div className="customer-welcome-copy">
          <span className="customer-eyebrow"><i /> YOUR ZENTRO SPACE</span>
          <h1>{firstName ? <>Welcome, <em>{firstName}.</em></> : <>Your parcel,<br /><em>in good hands.</em></>}</h1>
          <p>Send with care, follow every step, and keep your deliveries close.</p>
          <Link className="customer-primary-button" to="/customer/create-delivery">Send a parcel <ArrowIcon /></Link>
        </div>
        <div className="customer-welcome-art" aria-hidden="true">
          <div className="customer-art-sun" />
          <div className="customer-route-line"><i /><i /><i /></div>
          <div className="customer-parcel-box"><span>z</span><i /><i /><i /></div>
          <div className="customer-art-note"><span className="customer-note-mark">z</span><span><b>Made for Sri Lanka</b><small>Every parcel, cared for</small></span></div>
        </div>
      </section>

      <section className="customer-actions-section" aria-labelledby="customer-actions-title">
        <div className="customer-section-heading">
          <div><span className="customer-eyebrow">A GOOD PLACE TO START</span><h2 id="customer-actions-title">What would you like to do?</h2></div>
          <p>Your delivery tools, all in one place.</p>
        </div>
        <div className="customer-action-grid">
          {actions.map(({ to, eyebrow, title, description, icon }) => (
            <Link className="customer-action-card" to={to} key={title}>
              <span className="customer-action-icon">{icon}</span>
              <span className="customer-action-eyebrow">{eyebrow}</span>
              <strong>{title}</strong>
              <span className="customer-action-description">{description}</span>
              <span className="customer-action-arrow"><ArrowIcon /></span>
            </Link>
          ))}
        </div>
      </section>

      <section className="customer-care-note">
        <span className="customer-care-mark">z</span>
        <div><strong>A little more peace of mind.</strong><p>Once you send a parcel, its updates and delivery details will be available from My parcels.</p></div>
        <Link to="/customer/parcels">Go to My parcels <ArrowIcon /></Link>
      </section>
      <footer className="customer-footer"><span>Thoughtful delivery, made for Sri Lanka.</span><Link to="/">Visit Zentro</Link></footer>
    </main>
  );
}

export const CreateDeliveryPage = () => <PortalPlaceholder title="Create delivery" role="CUSTOMER" />;
export const MyParcelsPage = () => <PortalPlaceholder title="My parcels" role="CUSTOMER" />;
export const ParcelDetailPage = () => <PortalPlaceholder title="Parcel details" role="CUSTOMER" />;
export const ReceiptPage = () => <PortalPlaceholder title="Receipt" role="CUSTOMER" />;
