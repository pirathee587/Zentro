import React, { createContext, useContext, useState, useCallback, useId } from 'react';
import { Button as BaseButton } from '@/shared/ui';
import { CircleCheckIcon, CircleAlertIcon } from './icons';

export interface ToastItem {
  id: string;
  title: string;
  message?: string;
  type?: 'success' | 'error' | 'info';
  durationMs?: number;
}

interface ToastContextType {
  toasts: ToastItem[];
  showToast: (toast: Omit<ToastItem, 'id'>) => string;
  removeToast: (id: string) => void;
}

const ToastContext = createContext<ToastContextType | undefined>(undefined);

export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [toasts, setToasts] = useState<ToastItem[]>([]);

  const removeToast = useCallback((id: string) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  const showToast = useCallback(
    ({ title, message, type = 'info', durationMs = 4000 }: Omit<ToastItem, 'id'>) => {
      const id = `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
      const newToast: ToastItem = { id, title, message, type, durationMs };
      setToasts((prev) => [...prev, newToast]);

      if (durationMs > 0) {
        setTimeout(() => {
          removeToast(id);
        }, durationMs);
      }
      return id;
    },
    [removeToast]
  );

  return (
    <ToastContext.Provider value={{ toasts, showToast, removeToast }}>
      {children}
      <div className="sd-toast-container" aria-live="polite" aria-atomic="true">
        {toasts.map((toast) => (
          <Toast key={toast.id} toast={toast} onClose={() => removeToast(toast.id)} />
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast() {
  const context = useContext(ToastContext);
  return context || {
    toasts: [],
    showToast: () => '',
    removeToast: () => {},
  };
}

export function Toast({ toast, onClose }: { toast: ToastItem; onClose?: () => void }) {
  const toastId = useId();

  return (
    <div
      id={toastId}
      role="status"
      className={`sd-toast sd-toast-${toast.type || 'info'}`}
    >
      <div className="sd-toast-icon" aria-hidden="true">
        {toast.type === 'error' ? (
          <CircleAlertIcon size={18} />
        ) : (
          <CircleCheckIcon size={18} />
        )}
      </div>
      <div className="sd-toast-body">
        <strong className="sd-toast-title">{toast.title}</strong>
        {toast.message && <p className="sd-toast-msg">{toast.message}</p>}
      </div>
      {onClose && (
        <BaseButton
          type="button"
          onClick={onClose}
          className="sd-toast-close"
          aria-label="Dismiss notification"
        >
          ×
        </BaseButton>
      )}
    </div>
  );
}
