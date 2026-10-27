import React, { useState, useEffect, useRef, useCallback, useContext } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Select as BaseSelect } from '@/shared/ui';
import { Card, CardHeader, CardContent } from '../components/Card';
import { Button } from '../components/Button';
import { Field } from '../components/Field';
import { Spinner } from '../components/Skeleton';
import { CircleCheckIcon, CircleAlertIcon, SendIcon } from '../components/icons';
import {
  estimateFee,
  createDelivery,
  BookingError,
  toBookingError,
  type FeeEstimateResponse,
  type Parcel,
} from '../services/customerApi';
import {
  type SendParcelForm,
  type FieldName,
  type FieldErrors,
  SIZE_OPTIONS,
  DESTINATION_OPTIONS,
  EMPTY_FORM,
  validateField,
  validateForm,
  mapServerFieldErrors,
  parseWeight,
} from './sendParcelForm';
import { AuthContext } from '@/shared/auth/authContext';

type Step = 1 | 2 | 3;

function generateIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID();
  }
  return `idem-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

export const SendParcelPage: React.FC = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const auth = useContext(AuthContext);

  // Flow step: 1 = Details, 2 = Review, 3 = Booked
  const [step, setStep] = useState<Step>(1);

  // Form state
  const [form, setForm] = useState<SendParcelForm>(() => {
    const queryDest = searchParams.get('dest');
    const queryWeight = searchParams.get('weight');
    const queryCategory = searchParams.get('category');
    return {
      ...EMPTY_FORM,
      dropoffAddress: queryDest || '',
      weightKg: queryWeight || '',
      parcelSize: queryCategory === 'Documents' ? 'SMALL' : 'MEDIUM',
    };
  });

  const [errors, setErrors] = useState<FieldErrors>({});
  const [serverErrorBanner, setServerErrorBanner] = useState<string | null>(null);

  // Fee estimate state
  const [feeEstimateResult, setFeeEstimateResult] = useState<FeeEstimateResponse | null>(null);
  const [estimating, setEstimating] = useState(false);
  const [estimateError, setEstimateError] = useState<string | null>(null);
  const estimateSeqRef = useRef(0);

  // Booking attempt state
  const [bookingLoading, setBookingLoading] = useState(false);
  const [bookingError, setBookingError] = useState<BookingError | null>(null);
  const [bookedParcel, setBookedParcel] = useState<Parcel | null>(null);

  // Idempotency key is retained across retries of the same booking attempt
  const idempotencyKeyRef = useRef<string>(generateIdempotencyKey());

  // Input change handler
  const handleFieldChange = (name: FieldName, value: string) => {
    setForm((prev) => ({ ...prev, [name]: value }));
    // Clear inline error on change
    setErrors((prev) => {
      if (!prev[name]) return prev;
      const copy = { ...prev };
      delete copy[name];
      return copy;
    });
    setServerErrorBanner(null);
  };

  // Field blur validation
  const handleFieldBlur = (name: FieldName) => {
    const errorMsg = validateField(name, form);
    if (errorMsg) {
      setErrors((prev) => ({ ...prev, [name]: errorMsg }));
    }
  };

  // Debounced fee calculation
  const triggerEstimate = useCallback(
    async (currentForm: SendParcelForm) => {
      const parsed = parseWeight(currentForm.weightKg);
      if (parsed === null) {
        setFeeEstimateResult(null);
        setEstimating(false);
        setEstimateError(null);
        return;
      }

      const seq = ++estimateSeqRef.current;
      setEstimating(true);
      setEstimateError(null);

      try {
        const response = await estimateFee({
          weightKg: parsed,
          destinationType: currentForm.destinationType,
          parcelSize: currentForm.parcelSize,
          pickupCity: currentForm.pickupAddress || 'Origin Hub',
          deliveryCity: currentForm.dropoffAddress || 'Destination Hub',
        });

        // Ignore stale responses
        if (seq !== estimateSeqRef.current) return;

        setFeeEstimateResult(response);
      } catch {
        if (seq !== estimateSeqRef.current) return;
        setFeeEstimateResult(null);
        setEstimateError('Unable to calculate fee. Please tap retry.');
      } finally {
        if (seq === estimateSeqRef.current) {
          setEstimating(false);
        }
      }
    },
    []
  );

  const formRef = useRef(form);
  formRef.current = form;

  useEffect(() => {
    const timer = setTimeout(() => {
      void triggerEstimate(formRef.current);
    }, 350);

    return () => clearTimeout(timer);
  }, [form.parcelSize, form.weightKg, form.destinationType, triggerEstimate]);

  // Handle Step 1 -> Step 2
  const handleProceedToReview = (e: React.FormEvent) => {
    e.preventDefault();
    const validationErrors = validateForm(form);
    if (Object.keys(validationErrors).length > 0) {
      setErrors(validationErrors);
      return;
    }

    if (!feeEstimateResult) {
      return;
    }

    setErrors({});
    setServerErrorBanner(null);
    setBookingError(null);
    // Keep idempotency key stable for this booking attempt
    if (!idempotencyKeyRef.current) {
      idempotencyKeyRef.current = generateIdempotencyKey();
    }
    setStep(2);
  };

  // Handle Confirm Booking in Step 2
  const handleConfirmBooking = async () => {
    if (bookingLoading) return;

    setBookingLoading(true);
    setBookingError(null);

    try {
      const result = await createDelivery(
        {
          sender: {
            name: 'Sender Contact',
            phone: form.pickupPhone,
            address: form.pickupAddress,
          },
          recipient: {
            name: form.recipientName,
            phone: form.recipientPhone,
            address: form.dropoffAddress,
          },
          parcel: {
            category: form.parcelSize === 'SMALL' ? 'Small Box' : form.parcelSize === 'LARGE' ? 'Large Box' : 'Medium Box',
            weightKg: Number(form.weightKg),
            size: form.parcelSize,
          },
          destinationType: form.destinationType,
        },
        {
          idempotencyKey: idempotencyKeyRef.current,
        }
      );

      setBookedParcel(result);
      setStep(3);
    } catch (err: unknown) {
      const bError = toBookingError(err);
      setBookingError(bError);

      // On 422 server validation errors: return to Step 1 and map errors
      if (bError.kind === 'validation') {
        const { fieldErrors, unmapped } = mapServerFieldErrors(bError.fieldErrors);
        setErrors(fieldErrors);
        if (unmapped.length > 0) {
          setServerErrorBanner(unmapped.join(', '));
        } else {
          setServerErrorBanner(bError.message || 'Please correct the highlighted fields.');
        }
        setStep(1);
      }
    } finally {
      setBookingLoading(false);
    }
  };

  const handleResetForm = () => {
    setForm(EMPTY_FORM);
    setErrors({});
    setServerErrorBanner(null);
    setFeeEstimateResult(null);
    setBookingError(null);
    setBookedParcel(null);
    idempotencyKeyRef.current = generateIdempotencyKey();
    setStep(1);
  };

  // Can review if inputs are valid and we have an estimate for current inputs
  // Prototype API simulation state
  const [prototypeStatus, setPrototypeStatus] = useState<string>('NORMAL');

  // Can review if inputs are valid and we have an estimate for current inputs
  const currentWeightNum = parseWeight(form.weightKg);
  const canProceedToReview = Boolean(
    currentWeightNum !== null &&
      feeEstimateResult &&
      !estimating &&
      !estimateError
  );

  return (
    <div className="sd-grid-12">
      {/* Page Title & Subtitle */}
      <div className="sd-col-span-12 sd-page-header">
        <h1 className="sd-page-title">Send a parcel</h1>
        <p className="sd-page-subtitle">
          Island-wide pickup and delivery. You see the fee before you confirm.
        </p>
      </div>

      {/* =====================================================================
          Left Card (span 7): 3-step indicator + step views
          ===================================================================== */}
      <Card span={7}>
        <CardHeader>
          <div style={{ width: '100%' }}>
            {/* 3-Step Indicator Pills */}
            <nav className="sd-step-pills" aria-label="Booking steps" style={{ marginBottom: '16px' }}>
              <span className={`sd-step-pill ${step === 1 ? 'is-active' : step > 1 ? 'is-done' : ''}`}>
                1 Details
              </span>
              <span className={`sd-step-pill ${step === 2 ? 'is-active' : step > 2 ? 'is-done' : ''}`}>
                2 Review
              </span>
              <span className={`sd-step-pill ${step === 3 ? 'is-active' : ''}`}>
                3 Booked
              </span>
            </nav>
            <span className="sr-only">
              {step === 1 && 'Send a Parcel'}
              {step === 2 && 'Review Your Delivery'}
              {step === 3 && 'Delivery Confirmed!'}
            </span>
          </div>
        </CardHeader>

        <CardContent>
          {/* Server Error Banner if 422 returned to Step 1 */}
          {serverErrorBanner && (
            <div className="sd-inline-alert sd-inline-alert-error" role="alert">
              <CircleAlertIcon size={18} />
              <span>{serverErrorBanner}</span>
            </div>
          )}

          {/* STEP 1: Details */}
          {step === 1 && (
            <form onSubmit={handleProceedToReview} noValidate>
              <div className="sd-form-grid-2">
                {/* Pickup Address (full width) */}
                <div className="sd-grid-col-full">
                  <Field
                    label="Pickup Address"
                    id="pickupAddress"
                    value={form.pickupAddress}
                    onChange={(e) => handleFieldChange('pickupAddress', e.target.value)}
                    onBlur={() => handleFieldBlur('pickupAddress')}
                    error={errors.pickupAddress}
                    placeholder="Street address, building, floor..."
                    required
                  />
                </div>

                {/* Pickup Contact Phone */}
                <div>
                  <Field
                    label="Pickup Contact Phone"
                    id="pickupPhone"
                    type="tel"
                    value={form.pickupPhone}
                    onChange={(e) => handleFieldChange('pickupPhone', e.target.value)}
                    onBlur={() => handleFieldBlur('pickupPhone')}
                    error={errors.pickupPhone}
                    placeholder="077 123 4567"
                    required
                  />
                </div>

                {/* Destination Dropdown */}
                <div className="sd-field">
                  <label htmlFor="destinationType" className="sd-label">
                    Destination <span className="sd-required-star" aria-hidden="true">*</span>
                  </label>
                  <BaseSelect
                    id="destinationType"
                    className="sd-input sd-select"
                    value={form.destinationType}
                    onChange={(e) => handleFieldChange('destinationType', e.target.value)}
                  >
                    {DESTINATION_OPTIONS.map((opt) => (
                      <option key={opt.value} value={opt.value}>
                        {opt.label}
                      </option>
                    ))}
                  </BaseSelect>
                </div>

                {/* Drop-off Address (full width) */}
                <div className="sd-grid-col-full">
                  <Field
                    label="Drop-off Address"
                    id="dropoffAddress"
                    value={form.dropoffAddress}
                    onChange={(e) => handleFieldChange('dropoffAddress', e.target.value)}
                    onBlur={() => handleFieldBlur('dropoffAddress')}
                    error={errors.dropoffAddress}
                    placeholder="Recipient street address, city..."
                    required
                  />
                </div>

                {/* Recipient Name */}
                <div>
                  <Field
                    label="Recipient Name"
                    id="recipientName"
                    value={form.recipientName}
                    onChange={(e) => handleFieldChange('recipientName', e.target.value)}
                    onBlur={() => handleFieldBlur('recipientName')}
                    error={errors.recipientName}
                    placeholder="Full name"
                    required
                  />
                </div>

                {/* Recipient Phone */}
                <div>
                  <Field
                    label="Recipient Phone"
                    id="recipientPhone"
                    type="tel"
                    value={form.recipientPhone}
                    onChange={(e) => handleFieldChange('recipientPhone', e.target.value)}
                    onBlur={() => handleFieldBlur('recipientPhone')}
                    error={errors.recipientPhone}
                    placeholder="071 987 6543"
                    required
                  />
                </div>

                {/* Parcel Size */}
                <div className="sd-field">
                  <label htmlFor="parcelSize" className="sd-label">
                    Parcel Size <span className="sd-required-star" aria-hidden="true">*</span>
                  </label>
                  <BaseSelect
                    id="parcelSize"
                    className="sd-input sd-select"
                    value={form.parcelSize}
                    onChange={(e) => handleFieldChange('parcelSize', e.target.value)}
                  >
                    {SIZE_OPTIONS.map((opt) => (
                      <option key={opt.value} value={opt.value}>
                        {opt.label}
                      </option>
                    ))}
                  </BaseSelect>
                </div>

                {/* Weight kg */}
                <div>
                  <Field
                    label="Weight (kg)"
                    id="weightKg"
                    type="number"
                    step="0.1"
                    min="0.1"
                    max="20"
                    value={form.weightKg}
                    onChange={(e) => handleFieldChange('weightKg', e.target.value)}
                    onBlur={() => handleFieldBlur('weightKg')}
                    error={errors.weightKg}
                    placeholder="e.g. 1.5"
                    required
                  />
                </div>
              </div>

              <div style={{ marginTop: '24px' }}>
                <Button
                  type="submit"
                  variant="accent"
                  disabled={!canProceedToReview}
                  style={{ borderRadius: '9999px', padding: '10px 24px' }}
                >
                  Review &amp; see fee
                </Button>
              </div>
            </form>
          )}

          {/* STEP 2: Review */}
          {step === 2 && (
            <div>
              {/* 503 / Network Error with Retry */}
              {bookingError?.kind === 'unavailable' && (
                <div className="sd-inline-alert sd-inline-alert-error" role="alert">
                  <CircleAlertIcon size={18} />
                  <div style={{ flex: 1 }}>
                    <p style={{ margin: 0, fontWeight: 600 }}>{bookingError.message}</p>
                    <p style={{ margin: '4px 0 0', fontSize: '12px' }}>
                      All your data is safe. Click retry to attempt booking again.
                    </p>
                  </div>
                  <Button
                    variant="outline"
                    size="small"
                    onClick={handleConfirmBooking}
                    disabled={bookingLoading}
                  >
                    Retry booking
                  </Button>
                </div>
              )}

              {/* 401 Session Expired (no retry; re-authenticate) */}
              {bookingError?.kind === 'unauthorized' && (
                <div className="sd-inline-alert sd-inline-alert-error" role="alert">
                  <CircleAlertIcon size={18} />
                  <div style={{ flex: 1 }}>
                    <p style={{ margin: 0, fontWeight: 600 }}>Session expired</p>
                    <p style={{ margin: '4px 0 0', fontSize: '12px' }}>
                      {bookingError.message}
                    </p>
                  </div>
                  <Button
                    variant="primary"
                    size="small"
                    onClick={() => {
                      if (auth?.login) {
                        void auth.login('/customer/send');
                      } else {
                        navigate('/login');
                      }
                    }}
                  >
                    Sign in again
                  </Button>
                </div>
              )}

              {/* Generic Booking Error */}
              {bookingError?.kind === 'unknown' && (
                <div className="sd-inline-alert sd-inline-alert-error" role="alert">
                  <CircleAlertIcon size={18} />
                  <div style={{ flex: 1 }}>
                    <p style={{ margin: 0 }}>{bookingError.message}</p>
                  </div>
                  <Button
                    variant="outline"
                    size="small"
                    onClick={handleConfirmBooking}
                    disabled={bookingLoading}
                  >
                    Retry
                  </Button>
                </div>
              )}

              {/* Summary Rows */}
              <div className="sd-review-list">
                <div className="sd-review-row">
                  <span className="sd-review-label">Pickup</span>
                  <span className="sd-review-value">{form.pickupAddress} ({form.pickupPhone})</span>
                </div>
                <div className="sd-review-row">
                  <span className="sd-review-label">Drop-off</span>
                  <span className="sd-review-value">{form.dropoffAddress}</span>
                </div>
                <div className="sd-review-row">
                  <span className="sd-review-label">Recipient</span>
                  <span className="sd-review-value">{form.recipientName} ({form.recipientPhone})</span>
                </div>
                <div className="sd-review-row">
                  <span className="sd-review-label">Parcel</span>
                  <span className="sd-review-value">
                    {SIZE_OPTIONS.find((s) => s.value === form.parcelSize)?.label} • {form.weightKg} kg
                  </span>
                </div>
                <div className="sd-review-row">
                  <span className="sd-review-label">Base + Weight</span>
                  <span className="sd-review-value">
                    LKR {((feeEstimateResult?.breakdown.baseFee || 0) + (feeEstimateResult?.breakdown.weightFee || 0)).toLocaleString()}
                  </span>
                </div>
                {Boolean(feeEstimateResult?.breakdown.distanceSurcharge) && (
                  <div className="sd-review-row">
                    <span className="sd-review-label">Surcharge</span>
                    <span className="sd-review-value">
                      LKR {(feeEstimateResult?.breakdown.distanceSurcharge || 0).toLocaleString()}
                    </span>
                  </div>
                )}
                <div className="sd-review-row is-total">
                  <span className="sd-review-label" style={{ fontWeight: 700, color: 'var(--ink)' }}>
                    Total
                  </span>
                  <span className="sd-review-value" style={{ fontWeight: 700, fontSize: '18px' }}>
                    {feeEstimateResult?.formattedFee}
                  </span>
                </div>
              </div>

              {/* Step 2 Actions */}
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <Button
                  variant="outline"
                  onClick={() => setStep(1)}
                  disabled={bookingLoading}
                >
                  Back
                </Button>
                <Button
                  variant="primary"
                  onClick={handleConfirmBooking}
                  disabled={bookingLoading || bookingError?.kind === 'unauthorized'}
                  loading={bookingLoading}
                >
                  {bookingLoading ? 'Booking...' : 'Confirm delivery'}
                </Button>
              </div>
            </div>
          )}

          {/* STEP 3: Booked */}
          {step === 3 && bookedParcel && (
            <div className="sd-booked-card">
              <div style={{ color: 'var(--ink)' }}>
                <CircleCheckIcon size={48} />
              </div>

              <h3 style={{ margin: 0, fontSize: '20px', fontWeight: 600 }}>
                Shipment Booked Successfully
              </h3>
              <p style={{ margin: 0, color: 'var(--mute)', fontSize: '14px' }}>
                Courier pickup has been scheduled for your doorstep.
              </p>

              {/* Exact tracking reference as returned by the server */}
              <div className="sd-booked-ref-box" aria-label="Assigned Tracking Reference">
                {bookedParcel.trackingRef}
              </div>

              <p style={{ margin: 0, color: 'var(--mute)', fontSize: '13px' }}>
                Save this tracking number to follow live hub dispatch updates.
              </p>

              <div style={{ display: 'flex', gap: '12px', marginTop: '8px', flexWrap: 'wrap' }}>
                <Button
                  variant="accent"
                  onClick={() => navigate(`/customer/track/${bookedParcel.trackingRef}`)}
                  icon={<SendIcon size={16} />}
                >
                  Track parcel
                </Button>
                <Button variant="outline" onClick={handleResetForm}>
                  Send another
                </Button>
              </div>
            </div>
          )}
        </CardContent>
      </Card>

      {/* =====================================================================
          Right Card (span 5): Fee Estimate
          ===================================================================== */}
      <Card span={5}>
        <div style={{ fontSize: '20px', fontWeight: 600, marginBottom: '4px' }}>Fee estimate</div>
        <div style={{ fontSize: '13px', color: 'var(--mute)', marginBottom: '18px' }}>Estimate ready.</div>

        <div className="sd-fee-card">
          {/* Loading State */}
          {estimating && (
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px', padding: '18px 0' }}>
              <Spinner size="small" />
              <span style={{ fontSize: '14px', color: 'var(--mute)' }}>Calculating your fee...</span>
            </div>
          )}

          {/* Error State with Retry Button */}
          {!estimating && estimateError && (
            <div style={{ padding: '14px 0' }} role="alert">
              <p style={{ color: 'var(--err)', margin: '0 0 10px', fontSize: '13px' }}>
                {estimateError}
              </p>
              <Button
                variant="outline"
                size="small"
                onClick={() => void triggerEstimate(form)}
              >
                Retry estimate
              </Button>
            </div>
          )}

          {/* Empty Hint State (e.g. invalid or empty weight) */}
          {!estimating && !estimateError && !feeEstimateResult && (
            <div style={{ padding: '24px 0', textAlign: 'center', color: 'var(--mute)' }}>
              <p style={{ margin: 0, fontSize: '14px' }}>
                Enter parcel size, weight, and destination to see your live rate.
              </p>
            </div>
          )}

          {/* Success State Breakdown */}
          {!estimating && !estimateError && feeEstimateResult && (
            <>
              <div className="sd-fee-breakdown" aria-label="Fee breakdown">
                <div
                  className="sd-fee-row"
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    padding: '8px 0',
                    borderBottom: '1px solid var(--line)',
                  }}
                >
                  <span style={{ color: 'var(--mute)' }}>Base fee</span>
                  <span style={{ fontWeight: 500 }}>
                    LKR {feeEstimateResult.breakdown.baseFee.toLocaleString()}
                  </span>
                </div>
                <div
                  className="sd-fee-row"
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    padding: '8px 0',
                    borderBottom: '1px solid var(--line)',
                  }}
                >
                  <span style={{ color: 'var(--mute)' }}>Weight charge</span>
                  <span style={{ fontWeight: 500 }}>
                    LKR {feeEstimateResult.breakdown.weightFee.toLocaleString()}
                  </span>
                </div>
                <div
                  className="sd-fee-row"
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    padding: '8px 0',
                    borderBottom: '1px solid var(--line)',
                  }}
                >
                  <span style={{ color: 'var(--mute)' }}>Distance surcharge</span>
                  <span style={{ fontWeight: 500 }}>
                    LKR {(feeEstimateResult.breakdown.distanceSurcharge || 0).toLocaleString()}
                  </span>
                </div>
                <div
                  className="sd-fee-row is-total"
                  style={{ borderTop: 'none', margin: '20px 0 6px' }}
                >
                  <span className="sr-only">Total fee</span>
                  <div
                    style={{
                      fontSize: '36px',
                      fontWeight: 400,
                      color: 'var(--ink)',
                      letterSpacing: '-0.5px',
                    }}
                  >
                    {feeEstimateResult.formattedFee}
                  </div>
                  <span className="sr-only">{feeEstimateResult.formattedFee}</span>
                </div>
              </div>

              <div
                style={{
                  fontSize: '12px',
                  color: 'var(--mute)',
                  lineHeight: 1.4,
                  marginBottom: '24px',
                }}
              >
                The fee comes from the pricing service and is locked when you confirm.
              </div>

              <div style={{ paddingTop: '16px', borderTop: '1px solid var(--line)' }}>
                <label
                  htmlFor="prototype-simulate-select"
                  style={{
                    fontSize: '12px',
                    color: 'var(--mute)',
                    display: 'block',
                    marginBottom: '6px',
                  }}
                >
                  Prototype only: simulate API response
                </label>
                <BaseSelect
                  id="prototype-simulate-select"
                  className="sd-input sd-select"
                  style={{ borderRadius: '9999px', padding: '10px 18px', fontSize: '13px' }}
                  value={prototypeStatus}
                  onChange={(e) => setPrototypeStatus(e.target.value)}
                >
                  <option value="NORMAL">Normal</option>
                  <option value="ERROR_500">Server error 500</option>
                  <option value="ERROR_429">Rate limited 429</option>
                  <option value="ERROR_422">Validation error 422</option>
                </BaseSelect>
              </div>
            </>
          )}
        </div>
      </Card>
    </div>
  );
};
