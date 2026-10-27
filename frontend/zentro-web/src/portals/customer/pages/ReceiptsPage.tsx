import React, { useEffect, useState, useMemo, useCallback } from 'react';
import { useLocation, useSearchParams } from 'react-router-dom';
import { Button as BaseButton } from '@/shared/ui';
import { Card, CardHeader, CardTitle, CardContent } from '../components/Card';
import { Button } from '../components/Button';
import { Skeleton } from '../components/Skeleton';
import { EmptyState } from '../components/EmptyState';
import { PackageIcon, PrinterIcon, DownloadIcon, CircleAlertIcon } from '../components/icons';
import {
  listParcels,
  listReceipts,
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
    const clean = parcel.trackingRef.replace(/^ZTR-SD/, '');
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

  const loadData = useCallback(async () => {
    setLoading(true);
    setError('');

    try {
      const [parcelsRes, receiptsRes] = await Promise.all([
        listParcels({ status: 'Delivered' }),
        listReceipts(),
      ]);

      // Failed deliveries must not appear here; strictly Delivered only
      const deliveredOnly = parcelsRes.parcels.filter(
        (p: Parcel) => p.status === 'Delivered'
      );

      setParcels(deliveredOnly);
      setReceipts(receiptsRes);

      if (deliveredOnly.length > 0) {
        // Preselect based on query param / state, otherwise the newest
        let preselected: Parcel | undefined;

        if (targetTrackingRef) {
          preselected = deliveredOnly.find(
            (p: Parcel) => p.trackingRef.toUpperCase() === targetTrackingRef.toUpperCase()
          );
        }

        if (!preselected && targetReceiptId) {
          const matchingReceipt = receiptsRes.find(
            (r: Receipt) =>
              r.id === targetReceiptId ||
              r.receiptNumber.toUpperCase() === targetReceiptId.toUpperCase()
          );
          if (matchingReceipt) {
            preselected = deliveredOnly.find(
              (p: Parcel) => p.trackingRef === matchingReceipt.trackingRef
            );
          }
        }

        setSelectedParcel(preselected || deliveredOnly[0]);
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
                {[1, 2, 3, 4, 5, 6].map((i) => (
                  <Skeleton key={i} height={20} style={{ margin: '10px 0' }} />
                ))}
              </div>
            </CardContent>
          </Card>
        </>
      )}

      {/* Error state with Retry */}
      {!loading && error && (
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
      {!loading && !error && parcels.length === 0 && (
        <Card span={12}>
          <EmptyState
            title="No delivered parcels yet"
            description="Receipts and payment confirmations are generated once your deliveries are completed."
            icon={<DownloadIcon size={34} />}
          />
        </Card>
      )}

      {/* Content: Two columns */}
      {!loading && !error && parcels.length > 0 && selectedParcel && breakdown && (
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
                  const isSelected = selectedParcel.trackingRef === parcel.trackingRef;
                  return (
                    <BaseButton
                      key={parcel.id || parcel.trackingRef}
                      type="button"
                      role="option"
                      aria-selected={isSelected}
                      className={`sd-receipt-list-row ${isSelected ? 'is-selected' : ''}`}
                      onClick={() => setSelectedParcel(parcel)}
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

          {/* Right card (span 7): Receipt "paper" preview */}
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
                    <span className="sd-receipt-label">Date</span>
                    <span className="sd-receipt-val">
                      {matchingReceipt?.formattedDate ||
                        selectedParcel.formattedDeliveredAt ||
                        selectedParcel.formattedCreatedAt ||
                        formatDate(selectedParcel.createdAt)}
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
        </>
      )}
      </div>
    </div>
  );
};

export default ReceiptsPage;
