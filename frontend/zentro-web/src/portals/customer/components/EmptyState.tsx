import React from 'react';
import { PackageIcon } from './icons';

export interface EmptyStateProps {
  title?: string;
  description?: string;
  icon?: React.ReactNode;
  action?: React.ReactNode;
  className?: string;
}

export const EmptyState: React.FC<EmptyStateProps> = ({
  title = 'No items found',
  description = 'There are no records to display at this time.',
  icon = <PackageIcon size={32} />,
  action,
  className = '',
}) => {
  return (
    <div className={`sd-empty-state ${className}`.trim()}>
      <div className="sd-empty-icon" aria-hidden="true">
        {icon}
      </div>
      <h3 className="sd-empty-title">{title}</h3>
      {description && <p className="sd-empty-desc">{description}</p>}
      {action && <div className="sd-empty-action">{action}</div>}
    </div>
  );
};
