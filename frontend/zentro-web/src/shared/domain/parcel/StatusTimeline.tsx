import type { ParcelStatus } from './types'; import { StatusBadge } from './StatusBadge';
export function StatusTimeline({statuses}:{statuses:ParcelStatus[]}) { return <ol className="space-y-2">{statuses.map((status)=><li key={status}><StatusBadge status={status}/></li>)}</ol>; }
