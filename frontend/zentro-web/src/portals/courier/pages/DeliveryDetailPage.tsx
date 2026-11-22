import { Link, useParams } from 'react-router-dom';
import { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/shared/auth/useAuth';
import { Button, Input } from '@/shared/ui';
import { acceptDelivery, deliveryAccessDenied, deliverySessionExpired, deliveryError, getDelivery, getProof, photoProofUploadUnavailable, rejectDelivery, submitProofPhoto, updateDeliveryStatus, type DeliverySummary, type ProofResponse } from '../api/deliveryApi';
import { ProofOfDeliveryForm } from './ProofOfDeliveryForm';
import { principalCacheScope, queryKeys } from '@/shared/api/queryKeys';
import { invalidateAfterCourierProof } from '@/shared/api/queryInvalidation';
import { useCourierTodayDashboard } from '../hooks/useCourierTodayDashboard';
import './DeliveryDetailPage.css';

const stateMessage = {
  PENDING: 'Proof submitted — pending dispatcher review. Delivery details remain available while it is reviewed.',
  COMPLETED: 'Delivery completed. Parcel service has confirmed delivery.',
  REJECTED: 'Proof recorded, but the parcel service rejected delivery completion. Contact dispatch; do not submit new proof.',
  BLOCKED: 'Proof recorded. Completion needs operational attention. Contact dispatch; do not submit new proof.',
};
export function DeliveryDetailPage() {
  const { deliveryId = '' } = useParams();
  const { user } = useAuth();
  return <DeliveryDetail key={`${principalCacheScope(user?.id)}:${deliveryId}`} deliveryId={deliveryId} />;
}
function DeliveryDetail({ deliveryId }: { deliveryId: string }) {
  const { user, login } = useAuth();
  const client = useQueryClient();
  const token = user?.accessToken || '';
  const scope = principalCacheScope(user?.id);
  const deliveryKey = queryKeys.courier.delivery(deliveryId, scope);
  const proofKey = queryKeys.courier.proof(deliveryId, scope);
  const dashboard = useCourierTodayDashboard(1, 50);
  const dashboardDelivery = dashboard.data?.dashboard.deliveries.find(item => item.parcelId === deliveryId);
  const [submissionFailure, setSubmissionFailure] = useState<unknown>(null);
  const [actionBusy, setActionBusy] = useState(false);
  const [actionNotice, setActionNotice] = useState('');
  const [rejectReason, setRejectReason] = useState('');
  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  function denyAccess(error: unknown) {
    if (!mounted.current || !(deliveryAccessDenied(error) || deliverySessionExpired(error))) return;
    // Refetch errors normally retain successful data. Clear evidence and any cached list entry.
    client.setQueryData(deliveryKey, null);
    client.setQueryData(proofKey, null);
    client.setQueryData(queryKeys.courier.deliveries(scope), (items: DeliverySummary[] | undefined) =>
      items?.filter(item => item.parcelId !== deliveryId));
    setSubmissionFailure(error);
  }
  async function protectedRead<T>(read: () => Promise<T>, isProof = false): Promise<T | null> {
    try {
      const result = await read();
      const ownershipError = client.getQueryState(deliveryKey)?.error;
      if (isProof && (deliveryAccessDenied(ownershipError) || deliverySessionExpired(ownershipError))) return null;
      return result;
    }
    catch (error) {
      if (deliveryAccessDenied(error) || deliverySessionExpired(error)) {
        if (!isProof) void client.cancelQueries({ queryKey: proofKey, exact: true });
        denyAccess(error);
      }
      throw error;
    }
  }
  const delivery = useQuery({ queryKey: deliveryKey, queryFn: ({ signal }) => protectedRead(() => getDelivery(deliveryId, token, signal)), enabled: Boolean(token), gcTime: 0, staleTime: 0 });
  const proof = useQuery({ queryKey: proofKey, queryFn: ({ signal }) => protectedRead(() => getProof(deliveryId, token, signal), true),
    enabled: Boolean(token && delivery.isSuccess), gcTime: 0,
    refetchInterval: query => query.state.data?.completionState === 'PENDING' ? 3000 : false });
  function saved(value: ProofResponse) {
    if (!mounted.current) return;
    client.setQueryData(proofKey, value);
    // Keep the successfully loaded assignment visible after a proof submission.
    void Promise.all([
      client.invalidateQueries({ queryKey: queryKeys.courier.dashboard(scope) }),
      client.invalidateQueries({ queryKey: queryKeys.courier.deliveries(scope) }),
    ]);
  }
  const failure = delivery.error || proof.error || submissionFailure;
  const unavailable = deliveryAccessDenied(failure);
  const expired = deliverySessionExpired(failure);
  const trackingRef = delivery.data?.trackingRef ?? dashboardDelivery?.trackingRef ?? null;
  const parcelStatus = delivery.data?.parcelStatus ?? dashboardDelivery?.status ?? null;
  const nextStatus = parcelStatus === 'CREATED' ? 'PICKED_UP'
    : parcelStatus === 'PICKED_UP' ? 'IN_TRANSIT'
      : parcelStatus === 'IN_TRANSIT' ? 'OUT_FOR_DELIVERY' : null;
  const canSubmitProof = delivery.data?.assignmentStatus === 'ACCEPTED' && parcelStatus === 'OUT_FOR_DELIVERY';
  return <main className="courier-main courier-delivery-detail">
    <Link className="staff-text-link" to="/courier">Back to my deliveries</Link>
    <section className="staff-hero"><div><p className="courier-detail-eyebrow">Courier delivery</p><h1>{trackingRef || 'Delivery details'}</h1>
      {!unavailable && <p className="courier-delivery-id">{trackingRef ? 'Tracking reference' : 'Tracking reference unavailable'}</p>}</div></section>
    {failure ? <div className="courier-alert is-error" role="alert"><span>{unavailable ? 'Delivery not available' : deliveryError(failure)}</span>
      {expired ? <Button onClick={() => void login(`/courier/deliveries/${deliveryId}`, undefined, 'staff')}>Sign in again</Button>
        : !unavailable && <Button onClick={() => { void delivery.refetch().then(result => { if (result.isSuccess) void proof.refetch(); }); }}>Refresh</Button>}</div>
      : delivery.isPending || proof.isPending ? <p role="status">Loading delivery…</p>
        : <section className="courier-section courier-card courier-detail-card">
          <div className="courier-detail-summary">
            <div className="courier-summary-item">
              <span>Assignment</span>
              <strong>{delivery.data!.assignmentStatus || 'PENDING_ACCEPTANCE'}</strong>
            </div>
            <div className="courier-summary-item">
              <span>Shipment</span>
              <strong>{parcelStatus?.replaceAll('_', ' ') || 'Status unavailable'}</strong>
            </div>
            <div className="courier-summary-item">
              <span>Assigned At</span>
              <strong>{new Date(delivery.data!.assignedAt).toLocaleString()}</strong>
            </div>
          </div>
          {trackingRef ? <p className="courier-tracking-line">Tracking Reference: <strong className="courier-tracking-code">{trackingRef}</strong></p>
            : <p className="courier-muted">Tracking information is not available for this assignment yet.</p>}
          {actionNotice && <p role="status" className="courier-notice">{actionNotice}</p>}
          {delivery.data!.assignmentStatus === 'PENDING_ACCEPTANCE' && (
            <div className="courier-assignment-controls">
              <div className="courier-action-row">
                <Button disabled={actionBusy} onClick={async () => {
                  setActionBusy(true); setActionNotice('');
                  try { await acceptDelivery(deliveryId, token); await invalidateAfterCourierProof(client, deliveryId, scope); await delivery.refetch(); setActionNotice('Assignment accepted.'); }
                  catch (e) { setActionNotice(deliveryError(e)); }
                  finally { setActionBusy(false); }
                }}>Accept assignment</Button>
                <Input
                  type="text"
                  value={rejectReason}
                  onChange={e => setRejectReason(e.target.value)}
                  placeholder="Rejection reason"
                  aria-label="Rejection reason"
                />
                <Button variant="secondary" disabled={actionBusy || !rejectReason.trim()} onClick={async () => {
                  setActionBusy(true); setActionNotice('');
                  try { await rejectDelivery(deliveryId, rejectReason.trim(), token); await invalidateAfterCourierProof(client, deliveryId, scope); await delivery.refetch(); setActionNotice('Assignment rejected.'); }
                  catch (e) { setActionNotice(deliveryError(e)); }
                  finally { setActionBusy(false); }
                }}>Reject assignment</Button>
              </div>
            </div>
          )}
          {delivery.data!.assignmentStatus === 'REJECTED' && (
            <p role="status">Assignment rejected{delivery.data!.rejectionReason ? `: ${delivery.data!.rejectionReason}` : '.'}</p>
          )}
          {delivery.data!.assignmentStatus === 'ACCEPTED' && !proof.data && nextStatus && (
            <div className="courier-action-row courier-status-action">
              <Button variant="secondary" disabled={actionBusy} onClick={async () => {
                  setActionBusy(true); setActionNotice('');
                  try { await updateDeliveryStatus(deliveryId, nextStatus, token); await invalidateAfterCourierProof(client, deliveryId, scope); await delivery.refetch(); setActionNotice(`Status updated to ${nextStatus}.`); }
                  catch (e) { setActionNotice(deliveryError(e)); }
                  finally { setActionBusy(false); }
                }}>Mark {nextStatus.replaceAll('_', ' ')}</Button>
            </div>
          )}
          {proof.data ? (
            <div className="courier-saved-proof-card">
              <h2>Saved Proof of Delivery</h2>
              <div className="courier-proof-status-banner" role="status">
                {stateMessage[proof.data.completionState]}
              </div>
              <dl className="courier-proof-fields">
                <dt>Proof ID</dt>
                <dd>{proof.data.proofId}</dd>
                <dt>Recipient Name</dt>
                <dd>{proof.data.recipientName}</dd>
                <dt>Delivery Notes</dt>
                <dd>{proof.data.notes || 'None'}</dd>
                <dt>Proof Reference</dt>
                <dd>{proof.data.proofReference}</dd>
                <dt>Submitted</dt>
                <dd>{new Date(proof.data.submittedAt).toLocaleString()}</dd>
                {proof.data.completedAt && (
                  <>
                    <dt>Completion Confirmed</dt>
                    <dd>{new Date(proof.data.completedAt).toLocaleString()}</dd>
                  </>
                )}
              </dl>
              <Button
                variant="secondary"
                className="courier-refresh-btn"
                onClick={() => void proof.refetch()}
              >
                Refresh completion state
              </Button>
            </div>
          ) : canSubmitProof ? <ProofOfDeliveryForm key={`${scope}:${deliveryId}`} save={async (request, photo, idempotencyKey, progress) => {
            try { return await submitProofPhoto(deliveryId, request, photo, token, idempotencyKey, progress); }
            catch (error) {
              // The currently running pre-POD S3 returns 404 for this new route. Do not
              // clear the delivery cache or claim the courier lost assignment ownership.
              if (!mounted.current || photoProofUploadUnavailable(error)) throw error;
              throw error;
            }
          }}
            reconcile={() => protectedRead(() => getProof(deliveryId, token), true)} saved={saved} />
            : <p className="courier-proof-hint" role="status">Proof of delivery becomes available once this accepted shipment is out for delivery.</p>}
        </section>}
  </main>;
}
