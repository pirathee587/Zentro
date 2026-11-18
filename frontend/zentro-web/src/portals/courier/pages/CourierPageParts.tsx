import type { ReactNode } from 'react';
import { useLocation } from 'react-router-dom';
import { AlertCircle, PackageOpen } from 'lucide-react';
import { Button } from '@/shared/ui';
import { useAuth } from '@/shared/auth/useAuth';
import { deliveryError, deliverySessionExpired, type CompletionState } from '../api/deliveryApi';
import { completionLabels } from './courierPageUtils';
import './CourierSecondaryPages.css';

export function CourierPage({
  title,
  description,
  children,
  action,
}: {
  title: string;
  description: string;
  children: ReactNode;
  action?: ReactNode;
}) {
  return (
    <main className="sd-page-container courier-secondary">
      <header className="cs-header">
        <div>
          <p className="cs-eyebrow">Courier / {title}</p>
          <h1>{title}</h1>
          <p className="cs-muted">{description}</p>
        </div>
        {action}
      </header>
      {children}
    </main>
  );
}
export function ReadState({
  loading,
  error,
  retry,
}: {
  loading?: boolean;
  error?: unknown;
  retry: () => void;
}) {
  const { login } = useAuth();
  const location = useLocation();
  if (loading)
    return (
      <section className="cs-card cs-state" role="status">
        <PackageOpen aria-hidden="true" />
        <p>Loading delivery records...</p>
      </section>
    );
  return (
    <section className="cs-card cs-state" role="alert">
      <AlertCircle aria-hidden="true" />
      <h2>Delivery records unavailable</h2>
      <p>{deliveryError(error)}</p>
      {deliverySessionExpired(error) ? (
        <Button onClick={() => void login(location.pathname + location.search, undefined, 'staff')}>
          Sign in again
        </Button>
      ) : (
        <Button onClick={retry}>Try again</Button>
      )}
    </section>
  );
}
export function ProofBadge({ state }: { state: CompletionState | null }) {
  return <span className="cs-badge">{state ? completionLabels[state] : 'Awaiting proof'}</span>;
}
