import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Button, Input } from '@/shared/ui';
import {
  PRICING_RULE_FIELDS,
  toPricingError,
  type PricingRule,
  type PricingRuleField,
  type PricingRuleUpdateRequest,
} from '../api/pricingApi';
import { formatMoney, formatWeightKg } from './pricingFormat';
import {
  RULE_FIELD_META,
  changedFields,
  ruleToFormValues,
  validateRuleForm,
  type RuleFormErrors,
  type RuleFormValues,
} from './pricingValidation';
import { usePricingRule, useUpdatePricingRule } from './usePricing';

type Notice = { tone: 'success' | 'error'; text: string };
type ChangeRow = { field: PricingRuleField; label: string; before: string; after: string };

function displayValue(field: PricingRuleField, value: number, currency: string): string {
  return RULE_FIELD_META[field].kind === 'money' ? formatMoney(value, currency) : formatWeightKg(value);
}

function displayDate(value: string | null): string | null {
  if (!value) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? null
    : new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(date);
}

/** Current pricing rule from the live API: view, edit with field checks, and save after a confirmation. */
export function PricingRuleForm() {
  const rule = usePricingRule();

  if (rule.isPending) {
    return <div className="staff-panel staff-empty" role="status">Loading the current pricing rule…</div>;
  }
  if (rule.isError) {
    const error = toPricingError(rule.error, 'Could not load the pricing rule. Please try again.');
    return (
      <div className="staff-panel staff-error" role="alert">
        <p>{error.message}</p>
        {error.kind !== 'forbidden' && error.kind !== 'unauthenticated' && (
          <Button variant="secondary" type="button" onClick={() => void rule.refetch()}>Try again</Button>
        )}
      </div>
    );
  }
  return <RuleEditor rule={rule.data} />;
}

function RuleEditor({ rule }: { rule: PricingRule }) {
  const update = useUpdatePricingRule();
  const [draft, setDraft] = useState<Partial<RuleFormValues>>({});
  const [serverErrors, setServerErrors] = useState<RuleFormErrors>({});
  const [confirming, setConfirming] = useState(false);
  const [notice, setNotice] = useState<Notice | null>(null);

  const values: RuleFormValues = { ...ruleToFormValues(rule), ...draft };
  const errors = validateRuleForm(values);
  const invalid = Object.keys(errors).length > 0;
  const changed = changedFields(values, rule);
  const canSave = !invalid && changed.length > 0 && !update.isPending;
  const updatedAt = displayDate(rule.updatedAt);

  const rows: ChangeRow[] = changed.map((field) => ({
    field,
    label: RULE_FIELD_META[field].label,
    before: displayValue(field, rule[field], rule.currency),
    after: displayValue(field, Number(values[field].trim()), rule.currency),
  }));

  function edit(field: PricingRuleField, value: string) {
    setDraft((current) => ({ ...current, [field]: value }));
    setServerErrors((current) => {
      const next = { ...current };
      delete next[field];
      return next;
    });
    setNotice(null);
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    if (canSave) setConfirming(true);
  }

  function save() {
    const amounts = Object.fromEntries(
      PRICING_RULE_FIELDS.map((field) => [field, Number(values[field].trim())]),
    ) as Record<PricingRuleField, number>;
    const request: PricingRuleUpdateRequest = { ...amounts, currency: rule.currency };
    update.mutate(request, {
      onSuccess: () => {
        setDraft({});
        setServerErrors({});
        setConfirming(false);
        setNotice({ tone: 'success', text: 'Pricing rule saved. New quotes use these values.' });
      },
      onError: (failure) => {
        const error = toPricingError(failure, 'Could not save the pricing rule. Your changes are kept; please try again.');
        const fieldErrors: RuleFormErrors = {};
        for (const field of PRICING_RULE_FIELDS) {
          if (error.fieldErrors[field]) fieldErrors[field] = error.fieldErrors[field];
        }
        setServerErrors(fieldErrors);
        setConfirming(false);
        const text = error.kind === 'validation' && Object.keys(fieldErrors).length > 0
          ? 'The server did not accept some values. Check the highlighted fields.'
          : error.kind === 'network' || error.kind === 'unavailable'
            ? `${error.message} Your changes are kept.`
            : error.message;
        setNotice({ tone: 'error', text });
      },
    });
  }

  return (
    <div className="staff-panel staff-pricing-rule">
      <div className="staff-pricing-meta">
        <span>Rule <strong>{rule.ruleName || 'Standard'}</strong></span>
        <span>Currency <strong>{rule.currency}</strong></span>
        {updatedAt && <span>Last updated <strong>{updatedAt}</strong></span>}
      </div>

      <form className="staff-pricing-form" noValidate onSubmit={submit} aria-label="Pricing rule">
        <div className="staff-pricing-grid">
          {PRICING_RULE_FIELDS.map((field) => {
            const meta = RULE_FIELD_META[field];
            const id = `pricing-rule-${field}`;
            const error = (draft[field] !== undefined ? errors[field] : undefined) ?? serverErrors[field];
            const label = meta.kind === 'money' ? `${meta.label} (${rule.currency})` : meta.label;
            return (
              <div className="staff-field" key={field}>
                <label htmlFor={id}>{label}</label>
                <Input
                  id={id}
                  name={field}
                  type="text"
                  inputMode="decimal"
                  autoComplete="off"
                  value={values[field]}
                  disabled={update.isPending}
                  aria-invalid={error ? true : undefined}
                  aria-describedby={error ? `${id}-error` : `${id}-hint`}
                  onChange={(event) => edit(field, event.target.value)}
                />
                {error
                  ? <span className="staff-field-error" id={`${id}-error`}>{error}</span>
                  : <span className="staff-field-hint" id={`${id}-hint`}>{meta.hint}</span>}
              </div>
            );
          })}
        </div>

        {notice && (
          <p
            className={notice.tone === 'success' ? 'staff-review-success' : 'staff-review-notice'}
            role={notice.tone === 'success' ? 'status' : 'alert'}
          >
            {notice.text}
          </p>
        )}

        <div className="staff-pricing-actions">
          <span className="staff-muted" aria-live="polite">
            {invalid
              ? 'Fix the highlighted fields to save.'
              : changed.length === 0
                ? 'No changes to save.'
                : `${changed.length} change${changed.length === 1 ? '' : 's'} ready to save.`}
          </span>
          <Button
            variant="secondary"
            type="button"
            disabled={Object.keys(draft).length === 0 || update.isPending}
            onClick={() => { setDraft({}); setServerErrors({}); setNotice(null); }}
          >
            Discard changes
          </Button>
          <Button type="submit" disabled={!canSave}>Save changes</Button>
        </div>
      </form>

      {confirming && (
        <ConfirmRuleChanges
          rows={rows}
          pending={update.isPending}
          onCancel={() => setConfirming(false)}
          onConfirm={save}
        />
      )}
    </div>
  );
}

type ConfirmProps = {
  rows: ChangeRow[];
  pending: boolean;
  onCancel: () => void;
  onConfirm: () => void;
};

/** Keyboard-usable confirmation: focus moves in, Tab stays inside, Escape cancels, focus returns on close. */
function ConfirmRuleChanges({ rows, pending, onCancel, onConfirm }: ConfirmProps) {
  const dialog = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    dialog.current?.focus();
    return () => { if (previous?.isConnected) previous.focus(); };
  }, []);

  return (
    <div className="staff-modal-overlay">
      <div
        className="staff-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="pricing-confirm-title"
        aria-describedby="pricing-confirm-description"
        tabIndex={-1}
        ref={dialog}
        onKeyDown={(event) => {
          if (event.key === 'Escape') {
            event.preventDefault();
            if (!pending) onCancel();
          }
          if (event.key === 'Tab') {
            const controls = dialog.current?.querySelectorAll<HTMLElement>('button:not(:disabled)');
            if (!controls?.length) { event.preventDefault(); return; }
            const first = controls[0];
            const last = controls[controls.length - 1];
            if (event.shiftKey && (document.activeElement === first || document.activeElement === dialog.current)) {
              event.preventDefault(); last.focus();
            } else if (!event.shiftKey && (document.activeElement === last || document.activeElement === dialog.current)) {
              event.preventDefault(); first.focus();
            }
          }
        }}
      >
        <div className="staff-modal-header">
          <div>
            <h2 id="pricing-confirm-title">Save pricing changes?</h2>
            <p id="pricing-confirm-description">New quotes and bookings use the new values as soon as they are saved.</p>
          </div>
        </div>
        <table className="staff-pricing-changes">
          <caption className="sr-only">Pricing changes</caption>
          <thead>
            <tr><th scope="col">Setting</th><th scope="col">Current</th><th scope="col">New</th></tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.field}>
                <th scope="row">{row.label}</th>
                <td>{row.before}</td>
                <td><strong>{row.after}</strong></td>
              </tr>
            ))}
          </tbody>
        </table>
        {pending && <p role="status">Saving…</p>}
        <div className="staff-modal-footer">
          <Button variant="secondary" type="button" disabled={pending} onClick={onCancel}>Cancel</Button>
          <Button type="button" disabled={pending} onClick={onConfirm}>{pending ? 'Saving…' : 'Confirm and save'}</Button>
        </div>
      </div>
    </div>
  );
}
