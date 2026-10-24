import { useEffect } from 'react';
import { env } from '@/shared/config/env';
import { getAccessToken } from '@/shared/api/apiClient';

/** Opens an authenticated fetch stream and quietly reconnects if the gateway closes it. */
export function useTrackingStream(trackingNumber: string, onUpdate: () => void) {
  useEffect(() => {
    const controller = new AbortController();
    let reconnectTimer = 0;
    let stopped = false;
    const connect = async () => {
      const token = getAccessToken();
      if (!token) return;
      try {
        const response = await fetch(`${env.VITE_API_BASE_URL}/api/v1/tracking/${encodeURIComponent(trackingNumber)}/events`, {
          headers: { Accept: 'text/event-stream', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
          signal: controller.signal,
        });
        if (!response.ok || !response.body) throw new Error('Tracking stream unavailable');
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        while (!stopped) {
          const { value, done } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          const events = buffer.split(/\r?\n\r?\n/);
          buffer = events.pop() ?? '';
          if (events.some((event) => event.split(/\r?\n/).some((line) => line.startsWith('data:')))) onUpdate();
        }
      } catch {
        // A reconnect is expected while a parcel has no new event source yet.
      } finally {
        if (!stopped) reconnectTimer = window.setTimeout(() => void connect(), 5000);
      }
    };
    void connect();
    return () => { stopped = true; controller.abort(); window.clearTimeout(reconnectTimer); };
  }, [onUpdate, trackingNumber]);
}
