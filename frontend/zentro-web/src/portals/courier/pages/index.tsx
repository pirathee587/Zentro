import { PortalPlaceholder } from '@/shared/ui';
import { useAuth } from '@/shared/auth/useAuth';

export function TodayDeliveriesPage() {
  const { user } = useAuth();
  const firstName = user?.name?.trim().split(/\s+/)[0] || 'Courier';

  return <main className="courier-main">
    <section className="staff-hero">
      <div><span className="staff-eyebrow">YOUR COURIER SPACE</span><h1>Welcome, {firstName}.</h1><p>Your delivery day starts here. Check your route and keep each handoff up to date.</p></div>
      <span className="courier-status"><i aria-hidden="true" /> Ready for assignments</span>
    </section>

    <section className="courier-section" aria-labelledby="courier-assignment-title">
      <div className="courier-assignment">
        <span className="courier-assignment-icon" aria-hidden="true">↗</span>
        <div><h2 id="courier-assignment-title">Your delivery board is ready</h2><p>Assigned deliveries will appear here, with pickup details, destination, and the next action for each parcel.</p><p className="courier-note">Live delivery assignments are not connected in this build yet.</p></div>
      </div>
    </section>

    <section className="courier-section" aria-labelledby="courier-steps-title">
      <h2 className="courier-section-title" id="courier-steps-title">Your delivery flow</h2>
      <div className="courier-steps">
        <article className="courier-step"><span>1</span><strong>Review the assignment</strong><p>Check the parcel, pickup point, and delivery address.</p></article>
        <article className="courier-step"><span>2</span><strong>Keep the parcel moving</strong><p>Record pickup and delivery progress as each handoff happens.</p></article>
        <article className="courier-step"><span>3</span><strong>Complete the delivery</strong><p>Confirm the final handoff so the customer can see the update.</p></article>
      </div>
    </section>
  </main>;
}

export const DeliveryDetailPage = () => <PortalPlaceholder title="Delivery details" role="COURIER" />;
