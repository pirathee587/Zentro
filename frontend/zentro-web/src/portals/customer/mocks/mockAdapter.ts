import type {
  DashboardData,
  FeeEstimateRequest,
  FeeEstimateResponse,
  CreateDeliveryRequest,
  CreateDeliveryOptions,
  DestinationType,
  Parcel,
  ListParcelsParams,
  ListParcelsResponse,
  Receipt,
  AssistantResponse,
  SupportMessagePayload,
  SupportTicket,
} from '../services/types';
import { formatMoney, formatDate } from '../services/formatters';
import {
  INITIAL_MOCK_PARCELS,
  INITIAL_MOCK_RECEIPTS,
  buildDashboardData,
} from './mockData';

// In-memory persistent state for the mock session
let parcelsStore: Parcel[] = [...INITIAL_MOCK_PARCELS];
let receiptsStore: Receipt[] = [...INITIAL_MOCK_RECEIPTS];
const supportTicketsStore: SupportTicket[] = [];

// Subscribers for real-time tracking
const parcelSubscribers = new Map<string, Set<(parcel: Parcel) => void>>();

// Idempotency-Key -> parcel created for that key (mirrors server-side idempotency)
const idempotentBookings = new Map<string, Parcel>();

function generateTrackingRef(): string {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  let rand = '';
  for (let i = 0; i < 8; i++) {
    rand += chars.charAt(Math.floor(Math.random() * chars.length));
  }
  return `ZTR-SD${rand}`;
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
    const q = priceQuote(payload.weightKg, payload.destinationType, payload.isExpress);

    return {
      fee: q.total,
      formattedFee: formatMoney(q.total),
      currency: 'LKR',
      estimatedDays: payload.isExpress ? 1 : payload.destinationType === 'OTHER_DISTRICT' ? 3 : 2,
      breakdown: {
        baseFee: q.baseFee,
        weightFee: q.weightFee,
        distanceSurcharge: q.distanceSurcharge,
        expressFee: q.expressFee > 0 ? q.expressFee : undefined,
      },
    };
  },

  async createDelivery(payload: CreateDeliveryRequest, options?: CreateDeliveryOptions): Promise<Parcel> {
    await new Promise((r) => setTimeout(r, 200));

    const existing = options?.idempotencyKey ? idempotentBookings.get(options.idempotencyKey) : undefined;
    if (existing) return { ...existing };

    const trackingRef = generateTrackingRef();
    const id = `pcl-${Date.now()}`;
    const fee = priceQuote(payload.parcel.weightKg, payload.destinationType, payload.isExpress).total;
    const now = new Date();
    const estDate = new Date(now.getTime() + (payload.isExpress ? 1 : 2) * 24 * 60 * 60 * 1000);
    const senderCity = payload.sender.city || payload.sender.address;
    const recipientCity = payload.recipient.city || payload.recipient.address;

    const newParcel: Parcel = {
      id,
      trackingRef,
      status: 'Created',
      sender: { ...payload.sender, city: senderCity },
      recipient: { ...payload.recipient, city: recipientCity },
      parcel: {
        description: payload.parcel.description || payload.parcel.category,
        weightKg: payload.parcel.weightKg,
        category: payload.parcel.category,
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
    if (options?.idempotencyKey) idempotentBookings.set(options.idempotencyKey, newParcel);

    // Also auto-generate a receipt
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

    return newParcel;
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
