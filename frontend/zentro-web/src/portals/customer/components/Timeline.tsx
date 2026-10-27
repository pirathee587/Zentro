import React from 'react';
import { PackageIcon, TruckIcon, CircleCheckIcon, CircleAlertIcon, MapPinIcon } from './icons';

export interface TimelineEvent {
  id: string;
  title: string;
  description?: string;
  timestamp: string;
  formattedDate?: string;
  location?: string;
  status: 'completed' | 'current' | 'upcoming' | 'failed';
}

export interface TimelineProps {
  events: TimelineEvent[];
  className?: string;
}

function getEventIcon(title: string, status: TimelineEvent['status']) {
  if (status === 'failed') return <CircleAlertIcon size={16} />;
  const lower = title.toLowerCase();
  if (lower.includes('delivered') || lower.includes('received')) {
    return <CircleCheckIcon size={16} />;
  }
  if (lower.includes('transit') || lower.includes('dispatch') || lower.includes('delivery')) {
    return <TruckIcon size={16} />;
  }
  if (lower.includes('pickup') || lower.includes('hub') || lower.includes('sorting')) {
    return <MapPinIcon size={16} />;
  }
  return <PackageIcon size={16} />;
}

export const Timeline: React.FC<TimelineProps> = ({ events, className = '' }) => {
  if (!events || events.length === 0) {
    return null;
  }

  return (
    <div className={`sd-timeline ${className}`.trim()} role="list" aria-label="Tracking history">
      {events.map((event, index) => {
        const isLast = index === events.length - 1;
        const icon = getEventIcon(event.title, event.status);

        return (
          <div
            key={event.id || index}
            role="listitem"
            className={`sd-timeline-item sd-timeline-status-${event.status} ${isLast ? 'is-last' : ''}`}
          >
            <div className="sd-timeline-node">
              <span className="sd-timeline-bullet" aria-hidden="true">
                {icon}
              </span>
              {!isLast && <span className="sd-timeline-line" aria-hidden="true" />}
            </div>

            <div className="sd-timeline-content">
              <div className="sd-timeline-header">
                <strong className="sd-timeline-title">{event.title}</strong>
                <span className="sd-timeline-time">
                  {event.formattedDate || event.timestamp}
                </span>
              </div>
              {event.location && (
                <div className="sd-timeline-location">
                  <MapPinIcon size={14} />
                  <span>{event.location}</span>
                </div>
              )}
              {event.description && (
                <p className="sd-timeline-desc">{event.description}</p>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
};
