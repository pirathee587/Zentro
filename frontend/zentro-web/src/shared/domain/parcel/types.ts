export const parcelStatuses = ['CREATED','PICKED_UP','IN_TRANSIT','OUT_FOR_DELIVERY','DELIVERED','FAILED'] as const;
export type ParcelStatus = typeof parcelStatuses[number];
export const allowedStatusTransitions:Record<ParcelStatus,ParcelStatus[]> = {CREATED:['PICKED_UP'],PICKED_UP:['IN_TRANSIT'],IN_TRANSIT:['OUT_FOR_DELIVERY'],OUT_FOR_DELIVERY:['DELIVERED','FAILED'],DELIVERED:[],FAILED:[]};
export interface Parcel { id:string; trackingNumber:string; status:ParcelStatus; }

export function parseParcelStatus(value: string): ParcelStatus | undefined {
  const normalized = value.trim().toUpperCase().replace(/[\s-]+/g, '_');
  switch (normalized) {
    case 'CREATED': return 'CREATED';
    case 'PICKED_UP': return 'PICKED_UP';
    case 'IN_TRANSIT': return 'IN_TRANSIT';
    case 'OUT_FOR_DELIVERY': return 'OUT_FOR_DELIVERY';
    case 'DELIVERED': return 'DELIVERED';
    case 'FAILED': return 'FAILED';
    default: return undefined;
  }
}
