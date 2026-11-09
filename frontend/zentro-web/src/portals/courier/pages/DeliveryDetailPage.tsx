import { Link, useParams } from 'react-router-dom';
import { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/shared/auth/useAuth';
import { Button } from '@/shared/ui';
import { deliveryAccessDenied, deliverySessionExpired, deliveryError, getDelivery, getProof, submitProof, type DeliverySummary, type ProofResponse } from '../api/deliveryApi';
import { ProofOfDeliveryForm } from './ProofOfDeliveryForm';
import { principalCacheScope, queryKeys } from '@/shared/api/queryKeys';
import { invalidateAfterCourierProof } from '@/shared/api/queryInvalidation';

const stateMessage = {
  PENDING: 'Proof recorded. Delivery completion is pending confirmation.',
  COMPLETED: 'Delivery completed. Parcel service has confirmed delivery.',
  REJECTED: 'Proof recorded, but the parcel service rejected delivery completion. Contact dispatch; do not submit new proof.',
  BLOCKED: 'Proof recorded. Completion needs operational attention. Contact dispatch; do not submit new proof.',
};
export function DeliveryDetailPage() {
  const { deliveryId = '' } = useParams();
  const { user, login } = useAuth();
  const client = useQueryClient();
  const token = user?.accessToken || '';
  const scope = principalCacheScope(user?.id);
  const deliveryKey = queryKeys.courier.delivery(deliveryId, scope);
  const proofKey = queryKeys.courier.proof(deliveryId, scope);
  const [submissionFailure, setSubmissionFailure] = useState<unknown>(null);
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
    void invalidateAfterCourierProof(client, deliveryId, scope);
  }
  const failure = delivery.error || proof.error || submissionFailure;
  const unavailable = deliveryAccessDenied(failure);
  const expired = deliverySessionExpired(failure);
  return <main className="courier-main">
    <Link className="staff-text-link" to="/courier">Back to my deliveries</Link>
    <section className="staff-hero"><div><h1>Delivery details</h1>{!unavailable && <p className="courier-delivery-id">{deliveryId}</p>}</div></section>
    {failure ? <div className="courier-alert is-error" role="alert"><span>{unavailable ? 'Delivery not available' : deliveryError(failure)}</span>
      {expired ? <Button onClick={() => void login(`/courier/deliveries/${deliveryId}`, undefined, 'staff')}>Sign in again</Button>
        : !unavailable && <Button onClick={() => { void delivery.refetch().then(result => { if (result.isSuccess) void proof.refetch(); }); }}>Refresh</Button>}</div>
      : delivery.isPending || proof.isPending ? <p role="status">Loading delivery…</p>
        : <section className="courier-section courier-card">
          <p>Assigned {new Date(delivery.data!.assignedAt).toLocaleString()}</p>
          {proof.data ? <><h2>Saved proof</h2><p role="status">{stateMessage[proof.data.completionState]}</p>
            <dl className="courier-proof-fields">
              <dt>Proof ID</dt><dd>{proof.data.proofId}</dd><dt>Recipient Name</dt><dd>{proof.data.recipientName}</dd>
              <dt>Delivery Notes</dt><dd>{proof.data.notes || 'None'}</dd><dt>Proof Reference</dt><dd>{proof.data.proofReference}</dd>
              <dt>Submitted</dt><dd>{new Date(proof.data.submittedAt).toLocaleString()}</dd>
              {proof.data.completedAt && <><dt>Completion confirmed</dt><dd>{new Date(proof.data.completedAt).toLocaleString()}</dd></>}
            </dl><Button onClick={() => void proof.refetch()}>Refresh completion state</Button>
          </> : <ProofOfDeliveryForm key={`${scope}:${deliveryId}`} save={async request => {
            try { return await submitProof(deliveryId, request, token); }
            catch (error) {
              if (!mounted.current) throw error;
              denyAccess(error);
              throw error;
            }
          }}
            reconcile={() => protectedRead(() => getProof(deliveryId, token), true)} saved={saved} />}
        </section>}
  </main>;
}
