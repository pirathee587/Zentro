import React from 'react';

export interface CardProps extends React.HTMLAttributes<HTMLDivElement> {
  children?: React.ReactNode;
  span?: 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12;
  variant?: 'default' | 'panel' | 'subtle';
}

export const Card = React.forwardRef<HTMLDivElement, CardProps>(
  ({ children, className = '', span, variant = 'default', style, ...props }, ref) => {
    const spanClass = span ? `sd-col-span-${span}` : '';
    const variantClass = variant !== 'default' ? `sd-card-${variant}` : '';

    return (
      <div
        ref={ref}
        className={`sd-card ${spanClass} ${variantClass} ${className}`.trim()}
        style={style}
        {...props}
      >
        {children}
      </div>
    );
  }
);
Card.displayName = 'Card';

export const CardHeader: React.FC<React.HTMLAttributes<HTMLDivElement>> = ({
  children,
  className = '',
  ...props
}) => (
  <div className={`sd-card-header ${className}`.trim()} {...props}>
    {children}
  </div>
);

export const CardTitle: React.FC<React.HTMLAttributes<HTMLHeadingElement>> = ({
  children,
  className = '',
  ...props
}) => (
  <h2 className={`sd-card-title ${className}`.trim()} {...props}>
    {children}
  </h2>
);

export const CardDescription: React.FC<React.HTMLAttributes<HTMLParagraphElement>> = ({
  children,
  className = '',
  ...props
}) => (
  <p className={`sd-card-desc ${className}`.trim()} {...props}>
    {children}
  </p>
);

export const CardContent: React.FC<React.HTMLAttributes<HTMLDivElement>> = ({
  children,
  className = '',
  ...props
}) => (
  <div className={`sd-card-content ${className}`.trim()} {...props}>
    {children}
  </div>
);

export const CardFooter: React.FC<React.HTMLAttributes<HTMLDivElement>> = ({
  children,
  className = '',
  ...props
}) => (
  <div className={`sd-card-footer ${className}`.trim()} {...props}>
    {children}
  </div>
);
