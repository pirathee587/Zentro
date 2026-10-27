import type { TimelineEvent } from '../components/Timeline';

export type ParcelStatus = 'Created' | 'In progress' | 'Delivered' | 'Failed';

export interface Parcel {
  id: string;
  trackingRef: string;
  status: ParcelStatus;
  rawStatus?: string;
  sender: {
    name: string;
    phone: string;
    address: string;
    city: string;
  };
  recipient: {
    name: string;
    phone: string;
    address: string;
    city: string;
  };
  parcel: {
    description: string;
    weightKg: number;
    category: string;
    size?: ParcelSize | string;
  };
  fee: number;
  formattedFee: string;
  createdAt: string;
  formattedCreatedAt: string;
  estimatedDeliveryDate?: string;
  formattedEstimatedDeliveryDate?: string;
  deliveredAt?: string;
  formattedDeliveredAt?: string;
  failureReason?: string;
  courier?: {
    name: string;
    initials?: string;
    role?: string;
    zone?: string;
    phone?: string;
    vehicle?: string;
  };
  timeline: TimelineEvent[];
}

export interface CourierAssignment {
  name: string;
  initials: string;
  role: string;
  zone: string;
  phone: string;
}

export interface Notification {
  id: string;
  icon: 'package' | 'truck' | 'check' | 'alert' | 'bell';
  title: string;
  body: string;
  time: string;
  isNew: boolean;
}

export interface DashboardData {
  greeting: string;
  userName?: string;
  activeCount: number;
  deliveredCount: number;
  failedCount: number;
  awaitingPickupCount: number;
  inTransitCount: number;
  totalParcels: number;
  totalSpent: number;
  formattedTotalSpent: string;
  monthlySpent: number;
  formattedMonthlySpent: string;
  recentParcels: Parcel[];
  courier?: CourierAssignment;
  notifications: Notification[];
}

export type DestinationType = 'SAME_CITY' | 'OTHER_DISTRICT';
export type ParcelSize = 'SMALL' | 'MEDIUM' | 'LARGE';

export interface FeeEstimateRequest {
  pickupCity?: string;
  deliveryCity?: string;
  destinationType?: DestinationType;
  parcelSize?: ParcelSize;
  weightKg: number;
  parcelCategory?: string;
  isExpress?: boolean;
}

export interface FeeEstimateResponse {
  fee: number;
  formattedFee: string;
  currency: string;
  estimatedDays: number;
  breakdown: {
    baseFee: number;
    weightFee: number;
    distanceSurcharge?: number;
    expressFee?: number;
  };
}

export interface CreateDeliveryRequest {
  sender: {
    name: string;
    phone: string;
    address: string;
    city?: string;
  };
  recipient: {
    name: string;
    phone: string;
    address: string;
    city?: string;
  };
  parcel: {
    description?: string;
    weightKg: number;
    category: string;
    size?: ParcelSize;
  };
  destinationType?: DestinationType;
  isExpress?: boolean;
  notes?: string;
}

export interface CreateDeliveryOptions {
  /** Sent as the Idempotency-Key header. Reuse the same key when retrying the same booking. */
  idempotencyKey: string;
}

export interface ListParcelsParams {
  status?: ParcelStatus | 'ALL' | string;
  search?: string;
  q?: string;
  page?: number;
  limit?: number;
}

export interface ListParcelsResponse {
  parcels: Parcel[];
  total: number;
  totalSpent?: number;
  formattedTotalSpent?: string;
}

export interface Receipt {
  id: string;
  receiptNumber: string;
  trackingRef: string;
  amount: number;
  formattedAmount: string;
  date: string;
  formattedDate: string;
  paymentMethod: string;
  paymentStatus: 'Paid' | 'Pending';
  downloadUrl: string;
  senderName: string;
  recipientName: string;
}

export interface AssistantResponse {
  reply: string;
  suggestedActions?: {
    label: string;
    url: string;
  }[];
}

export interface SupportMessagePayload {
  subject: string;
  category: 'delivery' | 'billing' | 'account' | 'general';
  trackingRef?: string;
  message: string;
}

export interface SupportTicket {
  id: string;
  ticketNumber: string;
  subject: string;
  category: string;
  trackingRef?: string;
  message: string;
  status: 'Open' | 'In progress' | 'Resolved';
  createdAt: string;
  formattedCreatedAt: string;
}
