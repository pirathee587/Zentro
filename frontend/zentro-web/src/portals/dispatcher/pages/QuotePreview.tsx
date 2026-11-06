import { useState, type FormEvent } from 'react';
import { Button, Checkbox, Input, Select } from '@/shared/ui';
import {
  toPricingError,
  type DestinationType,
  type FeeEstimateRequest,
  type FeeEstimateResponse,
  type ParcelSize,
  type PricingError,
} from '../api/pricingApi';
import { formatMoney } from './pricingFormat';
import { QUOTE_WEIGHT_MAX, QUOTE_WEIGHT_MIN, quoteWeightError } from './pricingValidation';
import { useFeeEstimate, useZones } from './usePricing';

const DESTINATIONS: { value: DestinationType; label: string }[] = [
  { value: 'SAME_CITY', label: 'Same city' },
  { value: 'OTHER_DISTRICT', label: 'Other district' },
];

const SIZES: { value: ParcelSize; label: string }[] = [
  { value: 'SMALL', label: 'Small' },
  { value: 'MEDIUM', label: 'Medium' },
  { value: 'LARGE', label: 'Large' },
];

type Inputs = {
  destinationType: DestinationType;
  parcelSize: ParcelSize;
  weightKg: string;
  isExpress: boolean;
  originZone: string;
  destinationZone: string;
};

const INITIAL: Inputs = {
  destinationType: 'SAME_CITY',
  parcelSize: 'SMALL',
  weightKg: '1',
  isExpress: false,
  originZone: '',
  destinationZone: '',
};

/**
 * Quotation preview: sends the inputs to the live estimate API (the same calculation customers and bookings use)
 * and shows the fee, the estimated days and every breakdown line the API returns. No price logic here.
 */
export function QuotePreview({ currency }: { currency: string }) {
  const zones = useZones();
  const estimate = useFeeEstimate();
  const [inputs, setInputs] = useState<Inputs>(INITIAL);
  const [weightTouched, setWeightTouched] = useState(false);

  const weightError = quoteWeightError(inputs.weightKg);
  const apiError: PricingError | null = estimate.isError
    ? toPricingError(estimate.error, 'Could not calculate the fee. Please try again.')
    : null;
  const fieldError = (field: keyof FeeEstimateRequest) => apiError?.fieldErrors[field];
  const shownWeightError = (weightTouched ? weightError : undefined) ?? fieldError('weightKg');

  function change<K extends keyof Inputs>(field: K, value: Inputs[K]) {
    setInputs((current) => ({ ...current, [field]: value }));
    if (!estimate.isIdle) estimate.reset();
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    setWeightTouched(true);
    if (weightError || estimate.isPending) return;
    const request: FeeEstimateRequest = {
      destinationType: inputs.destinationType,
      parcelSize: inputs.parcelSize,
      weightKg: Number(inputs.weightKg.trim()),
      isExpress: inputs.isExpress,
      currency,
      ...(inputs.originZone ? { originZone: inputs.originZone } : {}),
      ...(inputs.destinationZone ? { destinationZone: inputs.destinationZone } : {}),
    };
    estimate.mutate(request);
  }

  const zoneOptions = zones.data ?? [];
  const zonesUnavailable = zones.isError;

  return (
    <div className="staff-panel staff-pricing-quote">
      <form className="staff-pricing-form" noValidate onSubmit={submit} aria-label="Quotation preview">
        <div className="staff-pricing-grid">
          <div className="staff-field">
            <label htmlFor="quote-destination">Destination</label>
            <Select
              id="quote-destination"
              value={inputs.destinationType}
              aria-invalid={fieldError('destinationType') ? true : undefined}
              aria-describedby={fieldError('destinationType') ? 'quote-destination-error' : undefined}
              onChange={(event) => change('destinationType', event.target.value as DestinationType)}
            >
              {DESTINATIONS.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
            </Select>
            {fieldError('destinationType') && (
              <span className="staff-field-error" id="quote-destination-error">{fieldError('destinationType')}</span>
            )}
          </div>

          <div className="staff-field">
            <label htmlFor="quote-size">Parcel size</label>
            <Select id="quote-size" value={inputs.parcelSize}
              onChange={(event) => change('parcelSize', event.target.value as ParcelSize)}>
              {SIZES.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
            </Select>
          </div>

          <div className="staff-field">
            <label htmlFor="quote-weight">Weight (kg)</label>
            <Input
              id="quote-weight"
              type="text"
              inputMode="decimal"
              autoComplete="off"
              value={inputs.weightKg}
              aria-invalid={shownWeightError ? true : undefined}
              aria-describedby={shownWeightError ? 'quote-weight-error' : 'quote-weight-hint'}
              onChange={(event) => { setWeightTouched(true); change('weightKg', event.target.value); }}
            />
            {shownWeightError
              ? <span className="staff-field-error" id="quote-weight-error">{shownWeightError}</span>
              : <span className="staff-field-hint" id="quote-weight-hint">
                {QUOTE_WEIGHT_MIN}–{QUOTE_WEIGHT_MAX} kg, up to 2 decimals (same as customer bookings).
              </span>}
          </div>

          {(['originZone', 'destinationZone'] as const).map((field) => {
            const id = field === 'originZone' ? 'quote-origin' : 'quote-destination-zone';
            const error = fieldError(field);
            return (
              <div className="staff-field" key={field}>
                <label htmlFor={id}>{field === 'originZone' ? 'Origin zone (optional)' : 'Destination zone (optional)'}</label>
                <Select
                  id={id}
                  value={inputs[field]}
                  disabled={zones.isPending || zonesUnavailable}
                  aria-invalid={error ? true : undefined}
                  aria-describedby={error ? `${id}-error` : undefined}
                  onChange={(event) => change(field, event.target.value)}
                >
                  <option value="">No zone</option>
                  {zoneOptions.map((zone) => <option key={zone.code} value={zone.code}>{zone.name} ({zone.code})</option>)}
                </Select>
                {error && <span className="staff-field-error" id={`${id}-error`}>{error}</span>}
              </div>
            );
          })}

          <label className="staff-checkbox-label staff-pricing-check" htmlFor="quote-express">
            <Checkbox id="quote-express" checked={inputs.isExpress}
              onChange={(event) => change('isExpress', event.target.checked)} />
            Express delivery
          </label>
        </div>

        {zonesUnavailable && (
          <p className="staff-field-hint">Zones could not be loaded, so the preview runs without zones.</p>
        )}

        <div className="staff-pricing-actions">
          <Button type="submit" disabled={estimate.isPending}>{estimate.isPending ? 'Calculating…' : 'Calculate fee'}</Button>
        </div>
      </form>

      <div aria-live="polite" className="staff-pricing-result-region">
        {apiError && (
          <p className="staff-review-notice" role="alert">
            {apiError.kind === 'validation' && Object.keys(apiError.fieldErrors).length > 0
              ? `${apiError.message} Check the highlighted fields.`
              : apiError.message}
          </p>
        )}
        {estimate.isSuccess && <QuoteResult quote={estimate.data} />}
      </div>
    </div>
  );
}

function QuoteResult({ quote }: { quote: FeeEstimateResponse }) {
  const { breakdown, currency } = quote;
  const lines: { label: string; amount: number }[] = [
    { label: 'Base fee', amount: breakdown.baseFee },
    { label: 'Weight fee', amount: breakdown.weightFee },
    ...(breakdown.distanceSurcharge !== undefined ? [{ label: 'Distance surcharge', amount: breakdown.distanceSurcharge }] : []),
    ...(breakdown.expressFee !== undefined ? [{ label: 'Express fee', amount: breakdown.expressFee }] : []),
  ];
  return (
    <section className="staff-pricing-result" aria-label="Quoted fee">
      <div className="staff-pricing-total">
        <span>Delivery fee</span>
        <strong>{formatMoney(quote.fee, currency)}</strong>
        <small>Estimated delivery: {quote.estimatedDays} day{quote.estimatedDays === 1 ? '' : 's'}</small>
      </div>
      <dl className="staff-pricing-breakdown">
        {lines.map((line) => (
          <div key={line.label}>
            <dt>{line.label}</dt>
            <dd>{formatMoney(line.amount, currency)}</dd>
          </div>
        ))}
        <div className="staff-pricing-breakdown-total">
          <dt>Total</dt>
          <dd>{formatMoney(quote.fee, currency)}</dd>
        </div>
      </dl>
    </section>
  );
}
