import {
  BookingError,
  type DashboardData,
  type FeeEstimateRequest,
  type FeeEstimateResponse,
  type CreateDeliveryRequest,
  type CreateDeliveryOptions,
  type ParcelBookingResponse,
  type DestinationType,
  type Parcel,
  type ListParcelsParams,
  type ListParcelsResponse,
  type Receipt,
  type AssistantResponse,
  type SupportMessagePayload,
  type SupportTicket,
} from '../services/types';
import { formatMoney, formatDate } from '../services/formatters';
import {
  INITIAL_MOCK_PARCELS,
  INITIAL_MOCK_RECEIPTS,
  buildDashboardData,
} from './mockData';

// Session storage backing for mock session persistence across page reloads
function loadStoredParcels(): Parcel[] {
  if (typeof window !== 'undefined') {
    const raw = window.sessionStorage.getItem('zentro.mock-parcels');
    if (raw) {
      try {
        return JSON.parse(raw);
      } catch {
        // Fall back to initial mock parcels
      }
    }
  }
  return [...INITIAL_MOCK_PARCELS];
}

function saveStoredParcels(parcels: Parcel[]): void {
  if (typeof window !== 'undefined') {
    window.sessionStorage.setItem('zentro.mock-parcels', JSON.stringify(parcels));
  }
}

function loadStoredReceipts(): Receipt[] {
  if (typeof window !== 'undefined') {
    const raw = window.sessionStorage.getItem('zentro.mock-receipts');
    if (raw) {
      try {
        return JSON.parse(raw);
      } catch {
        // Fall back to initial mock receipts
      }
    }
  }
  return [...INITIAL_MOCK_RECEIPTS];
}

function saveStoredReceipts(receipts: Receipt[]): void {
  if (typeof window !== 'undefined') {
    window.sessionStorage.setItem('zentro.mock-receipts', JSON.stringify(receipts));
  }
}

// In-memory persistent state for the mock session
let parcelsStore: Parcel[] = loadStoredParcels();
let receiptsStore: Receipt[] = loadStoredReceipts();
const supportTicketsStore: SupportTicket[] = [];

// Subscribers for real-time tracking
const parcelSubscribers = new Map<string, Set<(parcel: Parcel) => void>>();

// Idempotency-Key -> normalized request signature and booking created for that key (mirrors server-side idempotency)
const idempotentBookings = new Map<
  string,
  { signature: string; booking: ParcelBookingResponse }
>();

const CROCKFORD_BASE32 = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

function generateTrackingRef(): string {
  let rand = '';
  for (let i = 0; i < 10; i++) {
    rand += CROCKFORD_BASE32.charAt(Math.floor(Math.random() * CROCKFORD_BASE32.length));
  }
  return `ZT-${rand}`;
}

function canonicalBookingSignature(payload: CreateDeliveryRequest): string {
  const emptyToNull = (val?: string) => {
    if (val === undefined || val === null) return null;
    const t = val.trim();
    return t.length > 0 ? t : null;
  };
  return JSON.stringify({
    destinationType: payload.destinationType ?? null,
    isExpress: Boolean(payload.isExpress),
    notes: emptyToNull(payload.notes),
    parcel: {
      category: payload.parcel.category.trim(),
      description: emptyToNull(payload.parcel.description),
      size: payload.parcel.size ?? null,
      weightKg: Math.round(payload.parcel.weightKg * 100) / 100,
    },
    recipient: {
      address: payload.recipient.address.trim(),
      city: emptyToNull(payload.recipient.city),
      name: payload.recipient.name.trim(),
      phone: payload.recipient.phone.replace(/[\s-]/g, ''),
    },
    sender: {
      address: payload.sender.address.trim(),
      city: emptyToNull(payload.sender.city),
      name: payload.sender.name.trim(),
      phone: payload.sender.phone.replace(/[\s-]/g, ''),
    },
  });
}

/** Mock "server" pricing. The UI must never replicate this; it only displays estimateFee() output. */
function priceQuote(weightKg: number, destinationType?: DestinationType, isExpress = false) {
  const baseFee = 550;
  const weightFee = Math.round(Math.max(0, weightKg - 1) * 120);
  const distanceSurcharge = destinationType === 'OTHER_DISTRICT' ? 402 : 0;
  const expressFee = isExpress ? 300 : 0;
  return { baseFee, weightFee, distanceSurcharge, expressFee, total: baseFee + weightFee + distanceSurcharge + expressFee };
}

export const mockCustomerAdapter = {
  async getDashboard(): Promise<DashboardData> {
    await new Promise((r) => setTimeout(r, 120));
    return buildDashboardData(parcelsStore);
  },

  async estimateFee(payload: FeeEstimateRequest): Promise<FeeEstimateResponse> {
    await new Promise((r) => setTimeout(r, 100));
    const defaultWeight =
      payload.parcelSize === 'SMALL' ? 1 : payload.parcelSize === 'LARGE' ? 5 : 3;
    const effectiveWeight =
      typeof payload.weightKg === 'number' && payload.weightKg > 0
        ? payload.weightKg
        : defaultWeight;
    const q = priceQuote(effectiveWeight, payload.destinationType, payload.isExpress);

    return {
      fee: q.total,
      formattedFee: formatMoney(q.total),
      currency: 'LKR',
      estimatedDays: payload.isExpress ? 1 : payload.destinationType === 'OTHER_DISTRICT' ? 3 : 2,
      breakdown: {
        baseFee: q.baseFee,
        weightFee: q.weightFee,
        ...(q.distanceSurcharge > 0 ? { distanceSurcharge: q.distanceSurcharge } : {}),
        ...(q.expressFee > 0 ? { expressFee: q.expressFee } : {}),
      },
    };
  },

  async createDelivery(
    payload: CreateDeliveryRequest,
    options?: CreateDeliveryOptions
  ): Promise<ParcelBookingResponse> {
    await new Promise((r) => setTimeout(r, 200));

    const signature = canonicalBookingSignature(payload);
    const key = options?.idempotencyKey?.trim();
    if (key) {
      const existing = idempotentBookings.get(key);
      if (existing) {
        if (existing.signature !== signature) {
          throw new BookingError(
            'conflict',
            'This booking conflicts with an earlier attempt. Review your details and try again',
            {},
            409
          );
        }
        return { ...existing.booking };
      }
    }

    const trackingRef = generateTrackingRef();
    const id = `pcl-${Date.now()}`;
    const fee = priceQuote(payload.parcel.weightKg, payload.destinationType, payload.isExpress).total;
    const estimatedDays = payload.isExpress
      ? 1
      : payload.destinationType === 'OTHER_DISTRICT'
      ? 3
      : 2;
    const now = new Date();
    const estDate = new Date(now.getTime() + estimatedDays * 24 * 60 * 60 * 1000);
    const senderCity = payload.sender.city?.trim() || payload.sender.address.trim();
    const recipientCity = payload.recipient.city?.trim() || payload.recipient.address.trim();

    const bookingResponse: ParcelBookingResponse = {
      trackingRef,
      status: 'CREATED',
      fee,
      currency: 'LKR',
      estimatedDays,
      createdAt: now.toISOString(),
      sender: {
        name: payload.sender.name.trim(),
        phone: payload.sender.phone.replace(/[\s-]/g, ''),
        address: payload.sender.address.trim(),
        ...(payload.sender.city?.trim() ? { city: payload.sender.city.trim() } : {}),
      },
      recipient: {
        name: payload.recipient.name.trim(),
        phone: payload.recipient.phone.replace(/[\s-]/g, ''),
        address: payload.recipient.address.trim(),
        ...(payload.recipient.city?.trim() ? { city: payload.recipient.city.trim() } : {}),
      },
      parcel: {
        weightKg: Math.round(payload.parcel.weightKg * 100) / 100,
        category: payload.parcel.category.trim(),
        ...(payload.parcel.size ? { size: payload.parcel.size } : {}),
        ...(payload.parcel.description?.trim()
          ? { description: payload.parcel.description.trim() }
          : {}),
      },
    };

    const newParcel: Parcel = {
      id,
      trackingRef,
      status: 'Created',
      sender: { ...bookingResponse.sender, city: senderCity },
      recipient: { ...bookingResponse.recipient, city: recipientCity },
      parcel: {
        description: payload.parcel.description || payload.parcel.category,
        weightKg: bookingResponse.parcel.weightKg,
        category: bookingResponse.parcel.category,
        size: bookingResponse.parcel.size,
      },
      fee,
      formattedFee: formatMoney(fee),
      createdAt: now.toISOString(),
      formattedCreatedAt: formatDate(now),
      estimatedDeliveryDate: estDate.toISOString(),
      formattedEstimatedDeliveryDate: formatDate(estDate),
      timeline: [
        {
          id: `ev-${Date.now()}-1`,
          title: 'Booking Confirmed',
          description: `Delivery request accepted with tracking ${trackingRef}`,
          timestamp: now.toISOString(),
          formattedDate: formatDate(now),
          location: senderCity,
          status: 'completed',
        },
        {
          id: `ev-${Date.now()}-2`,
          title: 'Scheduled for Courier Pickup',
          description: 'Courier assigned to collect parcel from sender address',
          timestamp: now.toISOString(),
          formattedDate: formatDate(now),
          location: senderCity,
          status: 'current',
        },
      ],
    };

    parcelsStore = [newParcel, ...parcelsStore];
    saveStoredParcels(parcelsStore);
    if (key) {
      idempotentBookings.set(key, { signature, booking: bookingResponse });
    }

    // Also auto-generate a demo receipt in mock mode
    const newReceipt: Receipt = {
      id: `rec-${Date.now()}`,
      receiptNumber: `REC-${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}${String(now.getDate()).padStart(2, '0')}-${parcelsStore.length.toString().padStart(2, '0')}`,
      trackingRef,
      amount: fee,
      formattedAmount: formatMoney(fee),
      date: now.toISOString(),
      formattedDate: formatDate(now),
      paymentMethod: 'Credit / Debit Card (Online)',
      paymentStatus: 'Paid',
      downloadUrl: `#receipt-${trackingRef}`,
      senderName: payload.sender.name,
      recipientName: payload.recipient.name,
    };
    receiptsStore = [newReceipt, ...receiptsStore];
    saveStoredReceipts(receiptsStore);

    return bookingResponse;
  },

  async listParcels(params?: ListParcelsParams): Promise<ListParcelsResponse> {
    await new Promise((r) => setTimeout(r, 120));
    let result = [...parcelsStore];

    if (params?.status && params.status !== 'ALL') {
      result = result.filter(
        (p) => p.status.toLowerCase() === params.status?.toLowerCase()
      );
    }

    const query = (params?.q || params?.search || '').toLowerCase().trim();
    if (query) {
      result = result.filter(
        (p) =>
          p.trackingRef.toLowerCase().includes(query) ||
          p.sender.city.toLowerCase().includes(query) ||
          p.sender.address.toLowerCase().includes(query) ||
          p.recipient.city.toLowerCase().includes(query) ||
          p.recipient.address.toLowerCase().includes(query) ||
          p.recipient.name.toLowerCase().includes(query) ||
          p.parcel.description.toLowerCase().includes(query)
      );
    }

    const total = result.length;
    const totalSpent = result.reduce((acc, p) => acc + (p.fee || 0), 0);

    if (params?.page && params?.limit) {
      const page = Math.max(1, params.page);
      const limit = params.limit;
      const start = (page - 1) * limit;
      result = result.slice(start, start + limit);
    }

    return {
      parcels: result,
      total,
      totalSpent,
      formattedTotalSpent: formatMoney(totalSpent),
    };
  },

  async getParcel(idOrRef: string): Promise<Parcel | null> {
    await new Promise((r) => setTimeout(r, 100));
    const normalized = idOrRef.trim().toUpperCase().replace(/[^A-Z0-9]/g, '');
    const found = parcelsStore.find(
      (p) =>
        p.id.toUpperCase() === normalized ||
        p.trackingRef.toUpperCase().replace(/[^A-Z0-9]/g, '') === normalized ||
        (normalized.length >= 5 && p.trackingRef.toUpperCase().replace(/[^A-Z0-9]/g, '').endsWith(normalized)) ||
        (normalized.startsWith('ZTR') && p.trackingRef === 'SD-20418')
    );
    return found ? { ...found } : null;
  },

  subscribeToParcel(idOrRef: string, callback: (parcel: Parcel) => void): () => void {
    const key = idOrRef.trim().toUpperCase();
    if (!parcelSubscribers.has(key)) {
      parcelSubscribers.set(key, new Set());
    }
    const set = parcelSubscribers.get(key)!;
    set.add(callback);

    // Initial emit
    const parcel = parcelsStore.find(
      (p) =>
        p.id === idOrRef ||
        p.trackingRef.toUpperCase() === key ||
        (key.startsWith('ZTR') && p.trackingRef === 'SD-20418')
    );
    if (parcel) {
      const emitParcel = key.startsWith('ZTR') ? { ...parcel, trackingRef: idOrRef } : { ...parcel };
      setTimeout(() => callback(emitParcel), 10);
    }

    return () => {
      set.delete(callback);
      if (set.size === 0) {
        parcelSubscribers.delete(key);
      }
    };
  },

  async listReceipts(): Promise<Receipt[]> {
    await new Promise((r) => setTimeout(r, 100));
    return [...receiptsStore];
  },

  async askAssistant(question: string): Promise<AssistantResponse> {
    await new Promise((r) => setTimeout(r, 250));
    const q = question.toLowerCase();

    if (q.includes('track') || q.includes('where is')) {
      return {
        reply:
          'You can track any shipment instantly using the tracking number starting with ZTR- on the Track page, or view all your current shipments under History.',
        suggestedActions: [
          { label: 'Go to Track', url: '/customer/track' },
          { label: 'View History', url: '/customer/history' },
        ],
      };
    }

    if (q.includes('fee') || q.includes('cost') || q.includes('rate') || q.includes('price')) {
      return {
        reply:
          'Standard door-to-door delivery within Western Province starts at LKR 550 for the first 1kg (+ LKR 250/kg). Island-wide delivery to other provinces takes 2-3 business days. Use the Send Parcel form to calculate an instant fee estimate.',
        suggestedActions: [{ label: 'Send Parcel & Estimate Fee', url: '/customer/send' }],
      };
    }

    if (q.includes('receipt') || q.includes('invoice') || q.includes('download')) {
      return {
        reply:
          'All your digital payment receipts and VAT invoices are securely stored in the Receipts section and can be downloaded as PDF anytime.',
        suggestedActions: [{ label: 'View Receipts', url: '/customer/receipts' }],
      };
    }

    return {
      reply:
        'I am the SwiftDrop Customer Assistant. I can help you book deliveries, calculate shipping costs, check status milestones, and find receipts.',
      suggestedActions: [
        { label: 'Send a parcel', url: '/customer/send' },
        { label: 'Track delivery', url: '/customer/track' },
        { label: 'Contact Support Team', url: '/customer/support' },
      ],
    };
  },

  async sendSupportMessage(message: SupportMessagePayload): Promise<SupportTicket> {
    await new Promise((r) => setTimeout(r, 200));
    const now = new Date();
    const ticket: SupportTicket = {
      id: `tick-${Date.now()}`,
      ticketNumber: `TICK-${now.getFullYear()}${String(now.getMonth() + 1).padStart(2, '0')}-${Math.floor(1000 + Math.random() * 9000)}`,
      subject: message.subject,
      category: message.category,
      trackingRef: message.trackingRef,
      message: message.message,
      status: 'Open',
      createdAt: now.toISOString(),
      formattedCreatedAt: formatDate(now),
    };
    supportTicketsStore.push(ticket);
    return ticket;
  },
};
