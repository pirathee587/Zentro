import React from 'react';
import { Button as BaseButton } from '@/shared/ui';

export type ButtonVariant = 'primary' | 'accent' | 'outline' | 'small' | 'ghost';

export interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: 'normal' | 'small';
  icon?: React.ReactNode;
  iconPosition?: 'left' | 'right';
  loading?: boolean;
}

export const Button: React.FC<ButtonProps> = ({
  children,
  className = '',
  variant = 'primary',
  size = 'normal',
  icon,
  iconPosition = 'left',
  loading = false,
  disabled,
  type = 'button',
  ...props
}) => {
  const isSmall = size === 'small' || variant === 'small';
  const variantClass = variant === 'small' ? 'sd-btn-outline' : `sd-btn-${variant}`;
  const sizeClass = isSmall ? 'sd-btn-small' : '';

  return (
    <BaseButton
      type={type}
      disabled={disabled || loading}
      className={`sd-btn ${variantClass} ${sizeClass} ${className}`.trim()}
      {...props}
    >
      {loading && <span className="sd-btn-spinner" aria-hidden="true" />}
      {!loading && icon && iconPosition === 'left' && (
        <span className="sd-btn-icon" aria-hidden="true">{icon}</span>
      )}
      {children && <span>{children}</span>}
      {!loading && icon && iconPosition === 'right' && (
        <span className="sd-btn-icon" aria-hidden="true">{icon}</span>
      )}
    </BaseButton>
  );
};
Button.displayName = 'Button';
