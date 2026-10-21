import { useEffect } from 'react';
/** Placeholder stream API; real SSE integration and polling behavior arrives in F3. */
export function useTrackingStream(trackingNumber:string,onUpdate:()=>void){useEffect(()=>{const poll=window.setInterval(onUpdate,10_000);return()=>window.clearInterval(poll);},[trackingNumber,onUpdate]);}
