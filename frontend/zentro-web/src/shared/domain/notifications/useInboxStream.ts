import { useEffect } from 'react';
import { env } from '@/shared/config/env';

/** Bearer-authenticated SSE: no query-string credentials and no native EventSource cookie assumptions. */
export function useInboxStream(portal: string, token: string | undefined, onUpdate: () => void) {
  useEffect(() => {
    if (!token) return;
    const controller = new AbortController();
    let stopped = false;
    let timer = 0;
    const connect = async () => {
      try {
        const response = await fetch(`${env.VITE_API_BASE_URL}/api/v1/notifications/events?portal=${encodeURIComponent(portal)}`, {
          headers: { Accept: 'text/event-stream', Authorization: `Bearer ${token}` }, signal: controller.signal,
        });
        if (response.status === 401 || response.status === 403 || response.status === 503) return;
        if (!response.ok || !response.body) throw new Error('Notification stream unavailable');
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        try {
          while (!stopped) {
            const { value, done } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            const events = buffer.split(/\r?\n\r?\n/);
            buffer = events.pop() ?? '';
            if (events.some(event => event.split(/\r?\n/).some(line => line.startsWith('data:')))) onUpdate();
          }
        } finally { await reader.cancel(); }
      } catch { /* Periodic REST refresh remains available during reconnection. */ }
      if (!stopped) timer = window.setTimeout(() => void connect(), 5000);
    };
    void connect();
    return () => { stopped = true; controller.abort(); window.clearTimeout(timer); };
  }, [portal, token, onUpdate]);
}
