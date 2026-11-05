import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import axios from 'axios';
import { useAuth } from '@/shared/auth/useAuth';
import { friendlyApiError } from '@/shared/api/apiClient';
import { Button } from '@/shared/ui';
import { assignDelivery, eligibleCouriers, type QueueParcel } from '../api/dispatchApi';

export function AssignmentDialog({ parcel, close, completed, stale }: {
  parcel: QueueParcel; close: () => void; completed: () => void; stale: (message: string) => void;
}) {
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const [selected, setSelected] = useState('');
  const [error, setError] = useState('');
  const dialog = useRef<HTMLDivElement>(null);
  const locked = useRef(false);
  const couriers = useQuery({ queryKey: ['dispatcher', 'eligible-couriers', user?.id, parcel.parcelId],
    queryFn: () => eligibleCouriers(parcel.parcelId, user!.accessToken!), enabled: Boolean(user?.accessToken), retry: false, staleTime: 0 });
  const validSelection = Boolean(selected && couriers.data?.some(courier => courier.id === selected));
  const mutation = useMutation({
    mutationFn: () => assignDelivery(parcel.parcelId, selected, user!.accessToken!),
    onSuccess: completed,
    onError: async (failure) => {
      const status = axios.isAxiosError(failure) ? failure.response?.status : undefined;
      const code = axios.isAxiosError(failure) ? failure.response?.data?.code : undefined;
      setSelected('');
      if ((status === 409 || status === 404) && code !== 'COURIER_INELIGIBLE' && code !== 'COURIER_NOT_FOUND') {
        stale(status === 409 ? 'This delivery is already assigned or no longer eligible. The queue has been refreshed.' : 'This delivery is no longer available. The queue has been refreshed.');
      } else {
        setError(status === 409 ? 'That courier is no longer eligible. Choose another courier.' : friendlyApiError(failure, 'Assignment failed. Refresh the queue before retrying if the result is uncertain.'));
        await queryClient.invalidateQueries({ queryKey: ['dispatcher', 'eligible-couriers', user?.id, parcel.parcelId] });
      }
    },
    onSettled: () => { locked.current = false; },
  });

  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const overflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    dialog.current?.focus();
    return () => { document.body.style.overflow = overflow; if (previous?.isConnected) previous.focus(); };
  }, []);

  useEffect(() => {
    if (couriers.isError && axios.isAxiosError(couriers.error)
        && [404, 409].includes(couriers.error.response?.status ?? 0)) {
      stale('This delivery is no longer available for assignment. The queue has been refreshed.');
    }
  }, [couriers.isError, couriers.error, stale]);

  return <div className="staff-modal-overlay">
    <div className="staff-modal" role="dialog" aria-modal="true" aria-labelledby="assignment-title" aria-describedby="assignment-description" tabIndex={-1} ref={dialog}
      onKeyDown={event => {
        if (event.key === 'Escape') { event.preventDefault(); if (!locked.current) close(); }
        if (event.key === 'Tab') {
          const controls = dialog.current?.querySelectorAll<HTMLElement>('button:not(:disabled), select:not(:disabled), [tabindex="0"]');
          if (!controls?.length) { event.preventDefault(); return; }
          const first = controls[0]; const last = controls[controls.length - 1];
          if (event.shiftKey && (document.activeElement === first || document.activeElement === dialog.current)) { event.preventDefault(); last.focus(); }
          else if (!event.shiftKey && (document.activeElement === last || document.activeElement === dialog.current)) { event.preventDefault(); first.focus(); }
        }
      }}>
      <h2 id="assignment-title">Assign delivery</h2>
      <p id="assignment-description">Select an eligible courier for {parcel.trackingReference || parcel.parcelId}. Eligibility is checked again when you confirm.</p>
      {error && <p className="staff-error" role="alert">{error}</p>}
      {couriers.isPending ? <p role="status">Loading eligible couriers…</p>
        : couriers.isError ? <div role="alert"><p>{friendlyApiError(couriers.error, 'Could not load eligible couriers.')}</p><Button onClick={() => void couriers.refetch()}>Retry couriers</Button></div>
          : !couriers.data?.length ? <p role="status">No eligible couriers are available. Update courier eligibility or try again later.</p>
            : <div className="staff-modal-field"><label htmlFor="assignment-courier">Courier</label>
              <select id="assignment-courier" value={validSelection ? selected : ''} disabled={mutation.isPending || couriers.isFetching} onChange={event => { setSelected(event.target.value); setError(''); }}>
                <option value="">Choose a courier</option>{couriers.data.map(courier => <option key={courier.id} value={courier.id}>{courier.name || 'Unnamed courier'} ({courier.id})</option>)}
              </select>
              {selected && !validSelection && <p role="alert">Your selected courier is no longer available. Choose another courier.</p>}
            </div>}
      {mutation.isPending && <p role="status">Assigning delivery…</p>}
      <div className="staff-modal-footer">
        <Button variant="secondary" disabled={mutation.isPending || couriers.isFetching} onClick={() => void couriers.refetch()}>Refresh couriers</Button>
        <Button variant="secondary" disabled={mutation.isPending} onClick={close}>Cancel</Button>
        <Button disabled={!validSelection || mutation.isPending || couriers.isFetching || couriers.isError} onClick={() => {
          if (locked.current || !validSelection) return;
          locked.current = true; setError(''); mutation.mutate();
        }}>{mutation.isPending ? 'Assigning…' : 'Confirm assignment'}</Button>
      </div>
    </div>
  </div>;
}
