import { useRef, useState } from 'react';
import axios from 'axios';
import { Button, Input, Textarea } from '@/shared/ui';
import { deliveryAccessDenied, deliverySessionExpired, deliveryError, proofLimits, type ProofResponse, type SubmitProofRequest } from '../api/deliveryApi';

interface Props {
  save: (request: SubmitProofRequest) => Promise<ProofResponse>;
  reconcile: () => Promise<ProofResponse | null>;
  saved: (proof: ProofResponse) => void;
}
export function ProofOfDeliveryForm({ save, reconcile, saved }: Props) {
  const [recipientName, setRecipientName] = useState('');
  const [notes, setNotes] = useState('');
  const [proofReference, setProofReference] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState('');
  const [pending, setPending] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const busy = useRef(false);
  const form = useRef<HTMLFormElement>(null);
  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (busy.current) return;
    const request = { recipientName: recipientName.trim(), notes: notes.trim() || null, proofReference: proofReference.trim() };
    const invalid: Record<string, string> = {};
    for (const field of ['recipientName', 'notes', 'proofReference'] as const) {
      if (field !== 'notes' && !request[field]) invalid[field] = 'This field is required.';
      else if (request[field]?.includes('\0')) invalid[field] = 'The null character is not allowed.';
      else if (Array.from(request[field] || '').length > proofLimits[field]) invalid[field] = `Use at most ${proofLimits[field]} characters.`;
    }
    setErrors(invalid); setError('');
    if (Object.keys(invalid).length) {
      form.current?.querySelector<HTMLElement>(`[name="${Object.keys(invalid)[0]}"]`)?.focus(); return;
    }
    busy.current = true; setPending(true);
    try {
      const existing = await reconcile();
      if (existing) { saved(existing); return; }
      saved(await save(request)); setUncertain(false);
    } catch (failure) {
      if (deliveryAccessDenied(failure) || deliverySessionExpired(failure)) {
        setError(deliveryAccessDenied(failure) ? 'Delivery not available' : deliveryError(failure));
        return;
      }
      if (axios.isAxiosError(failure) && failure.response?.data?.fieldErrors) {
        const fields = failure.response.data.fieldErrors;
        setErrors(Object.fromEntries(['recipientName', 'notes', 'proofReference']
          .filter(field => typeof fields[field] === 'string').map(field => [field, 'Check this field.'])));
      }
      setError(deliveryError(failure)); setUncertain(true);
      try {
        const existing = await reconcile();
        if (existing) { saved(existing); setError(''); setUncertain(false); }
      } catch { /* Preserve inputs until the saved result can be checked. */ }
    } finally { busy.current = false; setPending(false); }
  }
  return <form ref={form} className="courier-form" onSubmit={event => void submit(event)} noValidate aria-busy={pending}>
    <h2>Proof of delivery</h2><p>Confirm the person who received this delivery. Saved evidence cannot be edited.</p>
    {error && <p className="courier-alert is-error" role="alert">{error}</p>}
    {uncertain && <p role="status">The saved result will be checked before another submission. Your entries are preserved.</p>}
    <div className="courier-form-group"><label htmlFor="pod-recipientName">Recipient Name (required)</label>
      <Input id="pod-recipientName" name="recipientName" value={recipientName} onChange={e => setRecipientName(e.target.value)}
        required disabled={pending} aria-invalid={Boolean(errors.recipientName)} aria-describedby="pod-recipientName-hint" />
      <span id="pod-recipientName-hint">{errors.recipientName || 'Maximum 100 characters.'}</span></div>
    <div className="courier-form-group"><label htmlFor="pod-notes">Delivery Notes (optional)</label>
      <Textarea id="pod-notes" name="notes" value={notes} onChange={e => setNotes(e.target.value)}
        disabled={pending} aria-invalid={Boolean(errors.notes)} aria-describedby="pod-notes-hint" />
      <span id="pod-notes-hint">{errors.notes || 'Maximum 500 characters. Do not include unnecessary personal details.'}</span></div>
    <div className="courier-form-group"><label htmlFor="pod-proofReference">Proof Reference (required)</label>
      <Input id="pod-proofReference" name="proofReference" value={proofReference} onChange={e => setProofReference(e.target.value)}
        required disabled={pending} aria-invalid={Boolean(errors.proofReference)} aria-describedby="pod-proofReference-hint" />
      <span id="pod-proofReference-hint">{errors.proofReference || 'Maximum 200 characters.'}</span></div>
    <Button type="submit" disabled={pending}>{pending ? 'Saving proof…' : uncertain ? 'Check and retry' : 'Submit proof'}</Button>
  </form>;
}
