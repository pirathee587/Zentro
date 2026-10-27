import React from 'react';
import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import {
  Card,
  CardTitle,
  Button,
  StatusChip,
  Field,
  Timeline,
  EmptyState,
  Skeleton,
} from './index';

describe('SwiftDrop Shared Components', () => {
  it('renders Card with 12-col grid span and title', () => {
    render(
      <Card span={6}>
        <CardTitle>Test Card</CardTitle>
      </Card>
    );
    expect(screen.getByText('Test Card')).toBeInTheDocument();
    const cardEl = screen.getByText('Test Card').closest('.sd-card');
    expect(cardEl).toHaveClass('sd-col-span-6');
  });

  it('renders Button variants with pill classes and accessible focus attributes', () => {
    const { rerender } = render(<Button variant="primary">Submit</Button>);
    expect(screen.getByRole('button', { name: 'Submit' })).toHaveClass('sd-btn-primary');

    rerender(<Button variant="accent">Accent</Button>);
    expect(screen.getByRole('button', { name: 'Accent' })).toHaveClass('sd-btn-accent');

    rerender(<Button variant="outline">Outline</Button>);
    expect(screen.getByRole('button', { name: 'Outline' })).toHaveClass('sd-btn-outline');
  });

  it('renders StatusChip with correct token variant classes', () => {
    const { rerender } = render(<StatusChip status="In progress" />);
    expect(screen.getByText('In progress').closest('.sd-status-chip')).toHaveClass('sd-status-in-progress');

    rerender(<StatusChip status="Delivered" />);
    expect(screen.getByText('Delivered').closest('.sd-status-chip')).toHaveClass('sd-status-delivered');

    rerender(<StatusChip status="Failed" />);
    expect(screen.getByText('Failed').closest('.sd-status-chip')).toHaveClass('sd-status-failed');

    rerender(<StatusChip status="Created" />);
    expect(screen.getByText('Created').closest('.sd-status-chip')).toHaveClass('sd-status-created');
  });

  it('renders Field with label and accessible inline error with role="alert"', () => {
    render(
      <Field
        label="Pickup Address"
        id="pickup-address"
        error="Address is required"
      />
    );
    expect(screen.getByLabelText(/Pickup Address/)).toBeInTheDocument();
    const errorEl = screen.getByRole('alert');
    expect(errorEl).toHaveTextContent('Address is required');
  });

  it('renders Timeline events with completed and current status', () => {
    render(
      <Timeline
        events={[
          {
            id: '1',
            title: 'Booking Confirmed',
            timestamp: '2026-10-27',
            status: 'completed',
          },
          {
            id: '2',
            title: 'In Transit',
            timestamp: '2026-10-27',
            status: 'current',
          },
        ]}
      />
    );
    expect(screen.getByText('Booking Confirmed')).toBeInTheDocument();
    expect(screen.getByText('In Transit')).toBeInTheDocument();
  });

  it('renders EmptyState with icon and description', () => {
    render(
      <EmptyState
        title="No packages"
        description="You have no active shipments."
      />
    );
    expect(screen.getByText('No packages')).toBeInTheDocument();
    expect(screen.getByText('You have no active shipments.')).toBeInTheDocument();
  });

  it('renders Skeleton loading placeholder with variant', () => {
    const { container } = render(<Skeleton variant="pill" height={32} />);
    expect(container.querySelector('.sd-skeleton-pill')).toBeInTheDocument();
  });
});
