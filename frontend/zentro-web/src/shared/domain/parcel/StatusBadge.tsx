import type { ParcelStatus } from './types';
const styles:Record<ParcelStatus,string>={CREATED:'bg-status-created',PICKED_UP:'bg-status-picked-up',IN_TRANSIT:'bg-status-in-transit',OUT_FOR_DELIVERY:'bg-status-out-for-delivery',DELIVERED:'bg-status-delivered',FAILED:'bg-status-failed'};
export function StatusBadge({status}:{status:ParcelStatus}) { return <span className={`rounded-full px-2 py-1 text-xs font-semibold text-surface ${styles[status]}`}>{status.replaceAll('_',' ')}</span>; }
