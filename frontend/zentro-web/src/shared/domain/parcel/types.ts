export const parcelStatuses = ['CREATED','PICKED_UP','IN_TRANSIT','OUT_FOR_DELIVERY','DELIVERED','FAILED'] as const;
export type ParcelStatus = typeof parcelStatuses[number];
export const allowedStatusTransitions:Record<ParcelStatus,ParcelStatus[]> = {CREATED:['PICKED_UP'],PICKED_UP:['IN_TRANSIT'],IN_TRANSIT:['OUT_FOR_DELIVERY'],OUT_FOR_DELIVERY:['DELIVERED','FAILED'],DELIVERED:[],FAILED:[]};
export interface Parcel { id:string; trackingNumber:string; status:ParcelStatus; }
