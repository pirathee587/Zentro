import React from 'react';

export interface ActionConfirmDialogProps {
  isOpen: boolean;
  title: string;
  message: string;
  confirmLabel: string;
  isDestructive?: boolean;
  isPending?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

export function ActionConfirmDialog({
  isOpen,
  title,
  message,
  confirmLabel,
  isDestructive = false,
  isPending = false,
  onConfirm,
  onCancel,
}: ActionConfirmDialogProps) {
  if (!isOpen) return null;

  return (
    <div
      className="staff-modal-overlay"
      role="dialog"
      aria-modal="true"
      aria-labelledby="confirm-dialog-title"
    >
      <div className="staff-modal" style={{ maxWidth: '480px' }}>
        <div className="staff-modal-header">
          <div>
            <span className="staff-eyebrow">CONFIRM ACTION</span>
            <h2 id="confirm-dialog-title">{title}</h2>
          </div>
          <button
            type="button"
            className="staff-modal-close"
            onClick={onCancel}
            disabled={isPending}
            aria-label="Cancel confirmation"
          >
            ×
          </button>
        </div>

        <div className="staff-modal-body">
          <p style={{ margin: 0, fontSize: '13px', lineHeight: 1.6, color: 'var(--espresso)' }}>
            {message}
          </p>
        </div>

        <div className="staff-modal-footer">
          <button
            type="button"
            className="staff-review-button"
            onClick={onCancel}
            disabled={isPending}
          >
            Cancel
          </button>
          <button
            type="button"
            className={`staff-review-button ${isDestructive ? '' : 'is-approve'}`}
            style={isDestructive ? { borderColor: 'var(--err)', color: 'var(--err)' } : undefined}
            onClick={onConfirm}
            disabled={isPending}
          >
            {isPending ? 'Processing…' : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}

