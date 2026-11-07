import { useCallback, useState, type FormEvent } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Button, EmptyState, ErrorState, Input, Spinner } from '@/shared/ui';
import { StatusTimeline } from '@/shared/domain/parcel/StatusTimeline';
import { parseParcelStatus } from '@/shared/domain/parcel/types';
import { getTracking } from '@/shared/domain/tracking/trackingApi';
import { useTrackingStream } from '@/shared/domain/tracking/useTrackingStream';
import { ArrowIcon } from '../assets/ArrowIcon';
import { normalizePublicTrackingNumber } from '../utils/trackingNumber';
import { queryKeys } from '@/shared/api/queryKeys';

const Arrow = ArrowIcon;

export function TrackingSearchPage() {
  return (
    <section className="auth-card">
      <span className="eyebrow">PARCEL TRACKING</span>
      <h1>Where's it<br /><em>headed?</em></h1>
      <p>Enter the tracking number from your booking.</p>
      <TrackForm />
    </section>
  );
}

function TrackForm() {
  const [value, setValue] = useState('');
  const [error, setError] = useState('');
  const navigate = useNavigate();

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const trackingNumber = normalizePublicTrackingNumber(value);
    if (!trackingNumber) {
      setError('Enter a valid tracking number: ZTR- followed by 10 letters or numbers.');
      return;
    }
    navigate(`/track/${trackingNumber}`);
  }

  return (
    <form onSubmit={submit}>
      <Input
        aria-label="Tracking number"
        value={value}
        onChange={(event) => { setValue(event.target.value); setError(''); }}
        placeholder="ZTR-ABCDEFG234"
      />
      {error && <ErrorState message={error} />}
      <Button className="auth-submit" type="submit">Track parcel <Arrow /></Button>
    </form>
  );
}

export function TrackingPage() {
  const { trackingNumber = '' } = useParams();
  const navigate = useNavigate();
  const tracking = useQuery({
    queryKey: queryKeys.tracking.parcel(trackingNumber),
    queryFn: async ({ signal }) => (await getTracking(trackingNumber, signal)).data,
  });
  const refreshTracking = useCallback(() => { void tracking.refetch(); }, [tracking.refetch]);
  useTrackingStream(trackingNumber, refreshTracking);

  const statuses = tracking.data?.timeline?.flatMap((milestone) => {
    const status = parseParcelStatus(milestone.status);
    return status ? [status] : [];
  });

  return (
    <section className="auth-card">
      <span className="eyebrow">TRACKING UPDATE</span>
      <h1>Your parcel's<br /><em>journey.</em></h1>
      <p>Tracking number: <b>{trackingNumber}</b></p>
      {tracking.isPending ? (
        <Spinner label="Loading parcel updates" />
      ) : tracking.isError ? (
        <ErrorState message="We couldn't load this parcel. Check the number and try again." />
      ) : statuses?.length ? (
        <StatusTimeline statuses={statuses} />
      ) : (
        <EmptyState message="No tracking updates are available yet." />
      )}
      <Button variant="secondary" onClick={() => navigate('/track')}>Track another parcel</Button>
    </section>
  );
}
