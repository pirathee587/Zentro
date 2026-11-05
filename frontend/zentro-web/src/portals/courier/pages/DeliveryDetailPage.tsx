import { Link, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/shared/auth/useAuth';
import { Button } from '@/shared/ui';
import { deliveryError, getDelivery, getProof, submitProof, type ProofResponse } from '../api/deliveryApi';
import { ProofOfDeliveryForm } from './ProofOfDeliveryForm';

const stateMessage = {
  PENDING: 'Proof recorded. Delivery completion is pending confirmation.',
  COMPLETED: 'Delivery completed. Parcel service has confirmed delivery.',
  REJECTED: 'Proof recorded, but the parcel service rejected delivery completion. Contact dispatch; do not submit new proof.',
  BLOCKED: 'Proof recorded. Completion needs operational attention. Contact dispatch; do not submit new proof.',
};
export function DeliveryDetailPage() {
  const { deliveryId = '' } = useParams();
  const { user } = useAuth();
  const client = useQueryClient();
  const token = user?.accessToken || '';
  const deliveryKey = ['courier', user?.id, 'delivery', deliveryId];
  const proofKey = ['courier', user?.id, 'proof', deliveryId];
  const delivery = useQuery({ queryKey: deliveryKey, queryFn: () => getDelivery(deliveryId, token), enabled: Boolean(token), retry: false, gcTime: 0 });
  const proof = useQuery({ queryKey: proofKey, queryFn: () => getProof(deliveryId, token),
    enabled: Boolean(token && delivery.isSuccess), retry: false, gcTime: 0,
    refetchInterval: query => query.state.data?.completionState === 'PENDING' ? 3000 : false });
  function saved(value: ProofResponse) {
    client.setQueryData(proofKey, value);
    void client.invalidateQueries({ queryKey: ['courier', user?.id, 'deliveries'] });
    void client.invalidateQueries({ queryKey: deliveryKey });
  }
  const failure = delivery.error || proof.error;
  return <main className="courier-main">
    <Link className="staff-text-link" to="/courier">Back to my deliveries</Link>
    <section className="staff-hero"><div><h1>Delivery details</h1><p className="courier-delivery-id">{deliveryId}</p></div></section>
    {failure ? <div className="courier-alert is-error" role="alert"><span>{deliveryError(failure)}</span>
      <Button onClick={() => { void delivery.refetch(); void proof.refetch(); }}>Refresh</Button></div>
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
          </> : <ProofOfDeliveryForm key={deliveryId} save={request => submitProof(deliveryId, request, token)}
            reconcile={() => getProof(deliveryId, token)} saved={saved} />}
        </section>}
  </main>;
}
