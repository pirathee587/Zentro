import React from 'react';

export interface SkeletonProps extends React.HTMLAttributes<HTMLDivElement> {
  width?: string | number;
  height?: string | number;
  radius?: string | number;
  variant?: 'text' | 'pill' | 'card' | 'circle';
}

export const Skeleton: React.FC<SkeletonProps> = ({
  width,
  height,
  radius,
  variant = 'text',
  className = '',
  style,
  ...props
}) => {
  const customStyle: React.CSSProperties = {
    ...style,
    ...(width ? { width } : {}),
    ...(height ? { height } : {}),
    ...(radius ? { borderRadius: radius } : {}),
  };

  return (
    <div
      className={`sd-skeleton sd-skeleton-${variant} ${className}`.trim()}
      style={customStyle}
      aria-hidden="true"
      {...props}
    />
  );
};

export interface SpinnerProps extends React.HTMLAttributes<HTMLDivElement> {
  size?: 'small' | 'normal' | 'large';
  label?: string;
}

export const Spinner: React.FC<SpinnerProps> = ({
  size = 'normal',
  label = 'Loading...',
  className = '',
  ...props
}) => {
  return (
    <div
      className={`sd-spinner-wrap sd-spinner-${size} ${className}`.trim()}
      role="status"
      aria-live="polite"
      {...props}
    >
      <div className="sd-spinner" aria-hidden="true" />
      <span className="sr-only">{label}</span>
    </div>
  );
};
