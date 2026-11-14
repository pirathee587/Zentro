import React from 'react';
import { describe, expect, it } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { CourierRoutePage } from '../CourierRoutePage';
import { CourierHistoryPage } from '../CourierHistoryPage';
import { CourierEarningsPage } from '../CourierEarningsPage';

describe('CourierRoutePage', () => {
  it('renders route stops and progress stats', () => {
    render(
      <MemoryRouter>
        <CourierRoutePage />
      </MemoryRouter>
    );

    expect(screen.getByRole('heading', { name: /^Route$/i })).toBeInTheDocument();
    expect(screen.getByText(/1\. Nugegoda/i)).toBeInTheDocument();
    expect(screen.getByText(/2\. Maharagama/i)).toBeInTheDocument();
    expect(screen.getByText(/3\. Boralesgamuwa/i)).toBeInTheDocument();
    expect(screen.getByText(/4\. Piliyandala/i)).toBeInTheDocument();
    expect(screen.getByText(/5\. Kottawa/i)).toBeInTheDocument();
    expect(screen.getByText(/6\. Homagama/i)).toBeInTheDocument();

    // Check progress
    expect(screen.getByText('2 of 6')).toBeInTheDocument();
    expect(screen.getByText('Remaining')).toBeInTheDocument();
    expect(screen.getByText('Next stop')).toBeInTheDocument();
    expect(screen.getAllByText('Boralesgamuwa').length).toBeGreaterThanOrEqual(1);

    // Check Open in Maps buttons
    const mapLinks = screen.getAllByRole('link', { name: /Open in Maps/i });
    expect(mapLinks.length).toBe(6);
  });
});

describe('CourierHistoryPage', () => {
  it('renders history table and filters by status', () => {
    render(
      <MemoryRouter>
        <CourierHistoryPage />
      </MemoryRouter>
    );

    expect(screen.getByRole('heading', { name: /^History$/i })).toBeInTheDocument();
    expect(screen.getByText(/18 deliveries · 83% success/i)).toBeInTheDocument();

    // Check items rendered
    expect(screen.getByText('ZT-1A4D7KQ2MP')).toBeInTheDocument();
    expect(screen.getByText('ZT-7V3H6TD1YJ')).toBeInTheDocument();

    // Filter by Delivered
    const deliveredTab = screen.getByRole('tab', { name: /^Delivered$/i });
    fireEvent.click(deliveredTab);

    expect(screen.getByText('ZT-1A4D7KQ2MP')).toBeInTheDocument();
    expect(screen.queryByText('ZT-7V3H6TD1YJ')).not.toBeInTheDocument();

    // Filter by Failed
    const failedTab = screen.getByRole('tab', { name: /^Failed$/i });
    fireEvent.click(failedTab);

    expect(screen.getByText('ZT-7V3H6TD1YJ')).toBeInTheDocument();
    expect(screen.queryByText('ZT-1A4D7KQ2MP')).not.toBeInTheDocument();

    // Filter back to All
    const allTab = screen.getByRole('tab', { name: /^All$/i });
    fireEvent.click(allTab);

    expect(screen.getByText('ZT-1A4D7KQ2MP')).toBeInTheDocument();
    expect(screen.getByText('ZT-7V3H6TD1YJ')).toBeInTheDocument();
  });
});

describe('CourierEarningsPage', () => {
  it('renders stats counters and 7-day chart', () => {
    render(
      <MemoryRouter>
        <CourierEarningsPage />
      </MemoryRouter>
    );

    expect(screen.getByRole('heading', { name: /^Earnings$/i })).toBeInTheDocument();
    expect(screen.getByText('12')).toBeInTheDocument();
    expect(screen.getByText('3')).toBeInTheDocument();
    expect(screen.getByText('80%')).toBeInTheDocument();

    expect(screen.getByText('Wed · 2')).toBeInTheDocument();
    expect(screen.getByText('Thu · 0')).toBeInTheDocument();
    expect(screen.getByText('Fri · 2')).toBeInTheDocument();
    expect(screen.getByText('Sat · 3')).toBeInTheDocument();
    expect(screen.getByText('Sun · 2')).toBeInTheDocument();
    expect(screen.getByText('Mon · 2')).toBeInTheDocument();
    expect(screen.getByText('Tue · 1')).toBeInTheDocument();

    expect(screen.getByText(/Payout amounts will appear here once pay rules are set up/i)).toBeInTheDocument();
  });
});

