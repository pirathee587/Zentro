import React, { useEffect, useState, useMemo, useCallback } from 'react';
import { useLocation, useSearchParams, Link } from 'react-router-dom';
import { Button as BaseButton } from '@/shared/ui';
import { Card, CardHeader, CardTitle, CardContent } from '../components/Card';
import { Button } from '../components/Button';
import { Skeleton } from '../components/Skeleton';
import { EmptyState } from '../components/EmptyState';
import {
  PackageIcon,
  PrinterIcon,
  DownloadIcon,
  CircleAlertIcon,
  ClockIcon,
} from '../components/icons';
import {
  listParcels,
  listReceipts,
  getParcel,
  type Parcel,
  type Receipt,
  formatMoney,
  formatDate,
} from '../services/customerApi';

function getParcelSizeLabel(parcel: Parcel): string {
  const size = parcel.parcel?.size || parcel.parcel?.category;
  const w = parcel.parcel?.weightKg || 1;
  let label = size;
  if (!label || !['Small', 'Medium', 'Large'].includes(label)) {
    label = w <= 1 ? 'Small' : w <= 5 ? 'Medium' : 'Large';
  }
  return `${label} (${w} kg)`;
}

function computeReceiptBreakdown(parcel: Parcel) {
  const total = parcel.fee || 650;
  const weight = parcel.parcel?.weightKg || 1;
  const baseFee = 550;
  const weightCharge = Math.round(Math.max(0, weight - 1) * 250);
  const distanceSurcharge = Math.max(0, total - (baseFee + weightCharge));

  return {
    baseFee: formatMoney(baseFee),
    weightCharge: formatMoney(weightCharge),
    distanceSurcharge: formatMoney(distanceSurcharge),
    total: parcel.formattedFee || formatMoney(total),
  };
}

function maskPaymentMethod(method?: string): string {
  if (!method) return 'Credit Card (•••• 4242)';
  if (method.includes('••••')) return method;
  if (/bank/i.test(method)) return `${method} (•••• 8821)`;
  if (/card/i.test(method)) return `${method} (•••• 4242)`;
  return `${method} (•••• 1024)`;
}

function getReceiptNumber(receipt?: Receipt, parcel?: Parcel): string {
  if (receipt?.receiptNumber) {
    return receipt.receiptNumber.replace(/^REC-/, 'RC-');
  }
  if (parcel?.trackingRef) {
    const clean = parcel.trackingRef.replace(/^ZTR-SD/, '').replace(/^ZT-/, '');
    return `RC-2026-${clean.slice(0, 4)}`;
  }
  return 'RC-2026-0001';
}

export const ReceiptsPage: React.FC = () => {
  const location = useLocation();
  const [searchParams] = useSearchParams();

  // Target from query param or router state
  const targetTrackingRef =
    searchParams.get('trackingRef') ||
    (location.state as { trackingRef?: string } | undefined)?.trackingRef;

  const targetReceiptId =
    searchParams.get('receiptId') ||
    (location.state as { receiptId?: string } | undefined)?.receiptId;

  const [parcels, setParcels] = useState<Parcel[]>([]);
  const [receipts, setReceipts] = useState<Receipt[]>([]);
  const [selectedParcel, setSelectedParcel] = useState<Parcel | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [authError, setAuthError] = useState(false);
  const [pendingParcel, setPendingParcel] = useState<{
    trackingRef: string;
    status: string;
    failureReason?: string;
  } | null>(null);
  const [notFoundRef, setNotFoundRef] = useState<string | null>(null);

  const loadData = useCallback(async () => {
    setLoading(true);
    setError('');
    setAuthError(false);
    setPendingParcel(null);
    setNotFoundRef(null);

    try {
      let parcelsRes;
      try {
        parcelsRes = await listParcels({ status: 'Delivered' });
      } catch (err: unknown) {
        const status =
          (err as { response?: { status?: number }; status?: number })?.response?.status ||
          (err as { status?: number })?.status;
        const msg = (err as Error)?.message || '';
        if (status === 401 || status === 403 || /unauthorized|forbidden/i.test(msg)) {
          setAuthError(true);
          return;
        }
        throw err;
      }

      let receiptsRes: Receipt[] = [];
      try {
        receiptsRes = await listReceipts();
      } catch {
        receiptsRes = [];
      }

      // Failed or in-progress deliveries must not appear in the delivered list
      const deliveredOnly = (parcelsRes.parcels || []).filter(
        (p: Parcel) => p.status === 'Delivered'
      );

      setParcels(deliveredOnly);
      setReceipts(receiptsRes);

      if (targetTrackingRef) {
        const cleanTarget = targetTrackingRef.trim().toUpperCase();
        const matched = deliveredOnly.find(
          (p: Parcel) => p.trackingRef.toUpperCase() === cleanTarget
        );

        if (matched) {
          setSelectedParcel(matched);
        } else {
          // Check whether the requested parcel exists in non-delivered state
          try {
            const single = await getParcel(cleanTarget);
            if (single && single.status !== 'Delivered') {
              setPendingParcel({
                trackingRef: cleanTarget,
                status: single.status,
                failureReason: single.failureReason,
              });
              setSelectedParcel(null);
            } else {
              setNotFoundRef(cleanTarget);
              setSelectedParcel(null);
            }
          } catch {
            setNotFoundRef(cleanTarget);
            setSelectedParcel(null);
          }
        }
      } else if (targetReceiptId) {
        const matchingReceipt = receiptsRes.find(
          (r: Receipt) =>
            r.id === targetReceiptId ||
            r.receiptNumber.toUpperCase() === targetReceiptId.toUpperCase()
        );
        const matched = matchingReceipt
          ? deliveredOnly.find((p: Parcel) => p.trackingRef === matchingReceipt.trackingRef)
          : undefined;

        if (matched) {
          setSelectedParcel(matched);
        } else if (deliveredOnly.length > 0) {
          setSelectedParcel(deliveredOnly[0]);
        } else {
          setSelectedParcel(null);
        }
      } else if (deliveredOnly.length > 0) {
        setSelectedParcel(deliveredOnly[0]);
      } else {
        setSelectedParcel(null);
      }
    } catch {
      setError('Unable to load delivered receipts. Please check your connection.');
    } finally {
      setLoading(false);
    }
  }, [targetTrackingRef, targetReceiptId]);

  useEffect(() => {
    void loadData();
  }, [loadData]);

  const matchingReceipt = useMemo(() => {
    if (!selectedParcel) return undefined;
    return receipts.find((r) => r.trackingRef === selectedParcel.trackingRef);
  }, [selectedParcel, receipts]);

  const breakdown = useMemo(() => {
    if (!selectedParcel) return null;
    return computeReceiptBreakdown(selectedParcel);
  }, [selectedParcel]);

  const receiptNum = useMemo(() => {
    return getReceiptNumber(matchingReceipt, selectedParcel || undefined);
  }, [matchingReceipt, selectedParcel]);

  const handlePrint = () => {
    if (typeof window !== 'undefined') {
      window.print();
    }
  };

  return (
    <div>
      {/* Page Title */}
      <div className="sd-page-header">
        <h1 className="sd-page-title">Receipts</h1>
        <p className="sd-page-subtitle">
          Delivered parcels. Print or save as PDF.
        </p>
      </div>

      <div className="sd-grid-12">
        {/* Loading Skeleton */}
        {loading && (
          <>
            <Card span={5} className="sd-receipt-left-col">
              <CardHeader>
                <CardTitle>Delivered</CardTitle>
              </CardHeader>
              <CardContent>
                <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
                  {[1, 2, 3].map((i) => (
                    <Skeleton key={i} height={48} style={{ borderRadius: '14px' }} />
                  ))}
                </div>
              </CardContent>
            </Card>
            <Card span={7} className="sd-receipt-right-col">
              <CardHeader>
                <CardTitle>Receipt preview</CardTitle>
              </CardHeader>
              <CardContent>
                <div className="sd-receipt-paper" style={{ borderStyle: 'solid' }}>
                  <Skeleton height={28} width="160px" style={{ margin: '0 auto 16px' }} />
                  {[1, 2, 3, 4, 5, 6, 7].map((i) => (
                    <Skeleton key={i} height={20} style={{ margin: '10px 0' }} />
                  ))}
                </div>
              </CardContent>
            </Card>
          </>
        )}

        {/* Unauthorized State */}
        {!loading && authError && (
          <Card span={12}>
            <EmptyState
              title="Authentication required"
              description="You must be signed in as an authorized customer to view your delivery receipts."
              icon={<CircleAlertIcon size={34} />}
              action={
                <Link
                  to="/login"
                  className="sd-btn sd-btn-accent sd-btn-small"
                  style={{ textDecoration: 'none', borderRadius: '9999px', display: 'inline-flex' }}
                >
                  Sign In
                </Link>
              }
            />
          </Card>
        )}

        {/* Error state with Retry */}
        {!loading && !authError && error && (
          <Card span={12}>
            <EmptyState
              title="Unable to load receipts"
              description={error}
              icon={<CircleAlertIcon size={34} />}
              action={
                <Button variant="accent" size="small" onClick={() => void loadData()}>
                  Retry
                </Button>
              }
            />
          </Card>
        )}

        {/* Empty state: No delivered parcels yet */}
        {!loading && !authError && !error && parcels.length === 0 && !pendingParcel && !notFoundRef && (
          <Card span={12}>
            <EmptyState
              title="No delivered parcels yet"
              description="Receipts and payment confirmations are generated once your deliveries are completed."
              icon={<DownloadIcon size={34} />}
            />
          </Card>
        )}

        {/* Content: Two columns */}
        {!loading && !authError && !error && (parcels.length > 0 || pendingParcel || notFoundRef) && (
          <>
            {/* Left card (span 5): Delivered list */}
            <Card span={5} className="sd-receipt-left-col">
              <CardHeader>
                <div>
                  <CardTitle>Delivered</CardTitle>
                  <span style={{ fontSize: '12px', color: 'var(--mute)' }}>
                    {parcels.length} {parcels.length === 1 ? 'receipt' : 'receipts'} available
                  </span>
                </div>
              </CardHeader>
              <CardContent>
                <div
                  className="sd-receipt-list"
                  role="listbox"
                  aria-label="Delivered deliveries list"
                >
                  {parcels.map((parcel) => {
                    const isSelected = selectedParcel?.trackingRef === parcel.trackingRef;
                    return (
                      <BaseButton
                        key={parcel.id || parcel.trackingRef}
                        type="button"
                        role="option"
                        aria-selected={isSelected}
                        className={`sd-receipt-list-row ${isSelected ? 'is-selected' : ''}`}
                        onClick={() => {
                          setPendingParcel(null);
                          setNotFoundRef(null);
                          setSelectedParcel(parcel);
                        }}
                      >
                        <div className="sd-receipt-row-main">
                          <span className="sd-receipt-row-ref">{parcel.trackingRef}</span>
                          <span className="sd-receipt-row-dest">
                            • {parcel.recipient?.city || 'Destination'}
                          </span>
                        </div>
                        <span className="sd-receipt-row-fee">
                          {parcel.formattedFee || formatMoney(parcel.fee || 0)}
                        </span>
                      </BaseButton>
                    );
                  })}
                </div>
              </CardContent>
            </Card>

            {/* Right card (span 7): Pending/Undelivered Notice */}
            {pendingParcel && (
              <Card span={7} className="sd-receipt-right-col">
                <EmptyState
                  title={
                    pendingParcel.status === 'Failed'
                      ? 'Delivery interrupted — Receipt unavailable'
                      : 'Delivery in progress — Receipt unavailable'
                  }
                  description={`Parcel ${pendingParcel.trackingRef} is currently "${pendingParcel.status}". Official payment receipts and delivery summaries are only generated once delivery is completed.`}
                  icon={<ClockIcon size={34} />}
                  action={
                    <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap', justifyContent: 'center' }}>
                      <Link
                        to={`/customer/track/${encodeURIComponent(pendingParcel.trackingRef)}`}
                        className="sd-btn sd-btn-accent sd-btn-small"
                        style={{ textDecoration: 'none', borderRadius: '9999px', display: 'inline-flex' }}
                      >
                        Track parcel ({pendingParcel.trackingRef})
                      </Link>
                      {parcels.length > 0 && (
                        <Button
                          variant="outline"
                          size="small"
                          onClick={() => {
                            setPendingParcel(null);
                            setNotFoundRef(null);
                            setSelectedParcel(parcels[0]);
                          }}
                        >
                          View delivered receipts
                        </Button>
                      )}
                    </div>
                  }
                />
              </Card>
            )}

            {/* Right card (span 7): Not Found Notice */}
            {!pendingParcel && notFoundRef && (
              <Card span={7} className="sd-receipt-right-col">
                <EmptyState
                  title="Delivery receipt not found"
                  description={`We could not locate any delivery matching tracking reference "${notFoundRef}". Please verify your reference.`}
                  icon={<CircleAlertIcon size={34} />}
                  action={
                    parcels.length > 0 ? (
                      <Button
                        variant="outline"
                        size="small"
                        onClick={() => {
                          setPendingParcel(null);
                          setNotFoundRef(null);
                          setSelectedParcel(parcels[0]);
                        }}
                      >
                        View available delivered receipts
                      </Button>
                    ) : undefined
                  }
                />
              </Card>
            )}

            {/* Right card (span 7): Receipt "paper" preview */}
            {!pendingParcel && !notFoundRef && selectedParcel && breakdown && (
              <Card span={7} className="sd-receipt-right-col">
                <CardHeader className="sd-receipt-card-header">
                  <CardTitle>Receipt preview</CardTitle>
                </CardHeader>

                <CardContent>
                  {/* Receipt Paper */}
                  <div className="sd-receipt-paper" id="printable-receipt">
                    {/* Header: SwiftDrop and Delivery receipt • RC-{number} */}
                    <div className="sd-receipt-paper-header">
                      <div className="sd-receipt-paper-brand">
                        <PackageIcon size={20} />
                        <span>SwiftDrop</span>
                      </div>
                      <div className="sd-receipt-paper-sub">
                        Delivery receipt • {receiptNum}
                      </div>
                    </div>

                    {/* Label/value rows with dotted dividers */}
                    <div className="sd-receipt-lines">
                      <div className="sd-receipt-line">
                        <span className="sd-receipt-label">Tracking number</span>
                        <span className="sd-receipt-val">{selectedParcel.trackingRef}</span>
                      </div>

                      <div className="sd-receipt-line">
                        <span className="sd-receipt-label">Booking reference</span>
                        <span className="sd-receipt-val">{selectedParcel.id || selectedParcel.trackingRef}</span>
                      </div>

                      <div className="sd-receipt-line">
                        <span className="sd-receipt-label">Booking date</span>
                        <span className="sd-receipt-val">
                          {selectedParcel.formattedCreatedAt ||
                            formatDate(selectedParcel.createdAt) ||
                            'Not recorded'}
                        </span>
                      </div>

                      <div className="sd-receipt-line">
                        <span className="sd-receipt-label">Delivery completion date</span>
                        <span className="sd-receipt-val">
                          {selectedParcel.formattedDeliveredAt ||
                            (selectedParcel.deliveredAt ? formatDate(selectedParcel.deliveredAt) : 'Delivered')}
                        </span>
                      </div>

                      <div className="sd-receipt-line">
                        <span className="sd-receipt-label">From</span>
                        <span className="sd-receipt-val">
                          {selectedParcel.sender?.city || 'Colombo'}
                        </span>
                      </div>

                      <div className="sd-receipt-line">
                        <span className="sd-receipt-label">To</span>
                        <span className="sd-receipt-val">
                          {selectedParcel.recipient?.city || 'Destination'}
                        </span>
                      </div>

                      <div className="sd-receipt-line">
                        <span className="sd-receipt-label">Parcel size and weight</span>
                        <span className="sd-receipt-val">
                          {getParcelSizeLabel(selectedParcel)}
                        </span>
                      </div>

                      <div className="sd-receipt-line">
                        <span className="sd-receipt-label">Base fee</span>
                        <span className="sd-receipt-val">{breakdown.baseFee}</span>
                      </div>

                      <div className="sd-receipt-line">
                        <span className="sd-receipt-label">Weight charge</span>
                        <span className="sd-receipt-val">{breakdown.weightCharge}</span>
                      </div>

                      <div className="sd-receipt-line">
                        <span className="sd-receipt-label">Distance surcharge</span>
                        <span className="sd-receipt-val">{breakdown.distanceSurcharge}</span>
                      </div>

                      <div className="sd-receipt-line">
                        <span className="sd-receipt-label">Payment method</span>
                        <span className="sd-receipt-val">
                          {maskPaymentMethod(matchingReceipt?.paymentMethod)}
                        </span>
                      </div>

                      <div className="sd-receipt-line" data-testid="receipt-pod-row">
                        <span className="sd-receipt-label">Proof of delivery</span>
                        <span className="sd-receipt-val" style={{ display: 'inline-flex', alignItems: 'center', gap: '6px' }}>
                          {selectedParcel.proofOfDelivery?.signedBy ? (
                            <>
                              <span
                                className="inline-flex items-center text-xs font-semibold px-2 py-0.5 rounded-full bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300"
                              >
                                Verified
                              </span>
                              <span>{selectedParcel.proofOfDelivery.signedBy}</span>
                            </>
                          ) : (
                            <span
                              style={{
                                fontSize: '11px',
                                padding: '2px 8px',
                                borderRadius: '9999px',
                                background: 'var(--line)',
                                color: 'var(--mute)',
                                fontWeight: 600,
                              }}
                            >
                              Unavailable
                            </span>
                          )}
                        </span>
                      </div>
                    </div>

                    {/* Proof of delivery note */}
                    <div style={{ padding: '8px 0 10px', fontSize: '12px', color: 'var(--mute)', textAlign: 'right' }}>
                      {selectedParcel.proofOfDelivery?.signedBy ? (
                        <span>
                          {selectedParcel.proofOfDelivery.notes
                            ? `Note: ${selectedParcel.proofOfDelivery.notes}`
                            : `Signed on delivery`}
                        </span>
                      ) : (
                        <span>Electronic signature or photo proof was not recorded for this delivery</span>
                      )}
                    </div>

                    {/* Larger Total row */}
                    <div className="sd-receipt-total-row">
                      <span className="sd-receipt-total-label">Total</span>
                      <span className="sd-receipt-total-amount">{breakdown.total}</span>
                    </div>
                  </div>

                  <div style={{ marginTop: '20px', display: 'flex', justifyContent: 'center' }}>
                    <Button
                      type="button"
                      variant="accent"
                      icon={<PrinterIcon size={16} />}
                      style={{ borderRadius: '9999px', padding: '10px 28px' }}
                      onClick={handlePrint}
                    >
                      Print / Save as PDF
                    </Button>
                  </div>
                </CardContent>
              </Card>
            )}
          </>
        )}
      </div>
    </div>
  );
};

export default ReceiptsPage;
