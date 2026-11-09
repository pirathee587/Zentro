import React, { useId } from 'react';
import { Input as BaseInput, Textarea as BaseTextarea } from '@/shared/ui';

export interface FieldProps extends React.InputHTMLAttributes<HTMLInputElement> {
  label: string;
  error?: string;
  hint?: string;
  containerClassName?: string;
  multiline?: boolean;
  rows?: number;
}

export const Field: React.FC<FieldProps> = ({
  label,
  error,
  hint,
  id: customId,
  className = '',
  containerClassName = '',
  multiline = false,
  rows = 3,
  disabled,
  'aria-describedby': describedBy,
  ...props
}) => {
  const generatedId = useId();
  const id = customId || generatedId;
  const errorId = `${id}-error`;
  const hintId = `${id}-hint`;
  const description = [describedBy, error ? errorId : hint ? hintId : undefined].filter(Boolean).join(' ') || undefined;

  return (
    <div className={`sd-field ${error ? 'has-error' : ''} ${containerClassName}`.trim()}>
      <label htmlFor={id} className="sd-label">
        {label}
        {props.required && <span className="sd-required-star" aria-hidden="true">*</span>}
      </label>

      {multiline ? (
        <BaseTextarea
          {...(props as React.TextareaHTMLAttributes<HTMLTextAreaElement>)}
          id={id}
          rows={rows}
          disabled={disabled}
          aria-invalid={Boolean(error)}
          aria-describedby={description}
          className={`sd-input sd-textarea ${className}`.trim()}
        />
      ) : (
        <BaseInput
          {...props}
          id={id}
          disabled={disabled}
          aria-invalid={Boolean(error)}
          aria-describedby={description}
          className={`sd-input ${className}`.trim()}
        />
      )}

        {hint && !error && (
          <p id={hintId} className="sd-hint">
            {hint}
          </p>
        )}

        {error && (
          <p id={errorId} className="sd-error" role="alert" aria-live="polite">
            {error}
          </p>
        )}
    </div>
  );
};
Field.displayName = 'Field';
