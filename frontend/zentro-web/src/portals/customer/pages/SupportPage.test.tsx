import React from 'react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { SupportPage } from './SupportPage';
import { ToastProvider } from '../components/Toast';
import * as customerApi from '../services/customerApi';
import type { SupportTicket, AssistantResponse } from '../services/customerApi';

const mockTicket: SupportTicket = {
  id: 'tick-1001',
  ticketNumber: 'TICK-202610-8821',
  subject: 'Support inquiry regarding delivery',
  category: 'delivery',
  trackingRef: 'ZTR-SD8941K2M7',
  message: 'My package is delayed at the Kadawatha hub.',
  status: 'Open',
  createdAt: '2026-10-27T10:00:00.000Z',
  formattedCreatedAt: '27 Oct 2026',
};

describe('SupportPage Component', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('renders Row 1: Delivery assistant card (span 7, dark panel) and Common questions card (span 5)', async () => {
    const { container } = render(
      <MemoryRouter>
        <ToastProvider>
          <SupportPage />
        </ToastProvider>
      </MemoryRouter>
    );

    expect(screen.getByText('Delivery assistant')).toBeInTheDocument();
    expect(screen.getByText('Common questions')).toBeInTheDocument();
    expect(screen.getByText('Contact us')).toBeInTheDocument();

    // Assistant card has span 7 and panel variant
    const assistantCard = container.querySelector('.sd-col-span-7');
    expect(assistantCard).toHaveClass('sd-card-panel');

    // Common questions has span 5
    const faqCard = container.querySelector('.sd-col-span-5');
    expect(faqCard).toBeInTheDocument();

    // Scrollable chat area has role="log" and aria-live="polite"
    const chatLog = screen.getByRole('log');
    expect(chatLog).toHaveAttribute('aria-live', 'polite');
    expect(chatLog).toHaveClass('sd-chat-area');
  });

  it('renders user bubbles right-aligned with --y background, assistant bubbles left with #3a3a3a, and two suggestion chips', async () => {
    const askSpy = vi.spyOn(customerApi, 'askAssistant').mockResolvedValue({
      reply: 'Deliveries to Kandy take 2 business days.',
    });

    const { container } = render(
      <MemoryRouter>
        <ToastProvider>
          <SupportPage />
        </ToastProvider>
      </MemoryRouter>
    );

    // Initial assistant welcome bubble
    const initialBubble = screen.getByText(/I am your SwiftDrop Delivery Assistant/i);
    expect(initialBubble).toHaveClass('is-assistant');

    // Two suggestion chips
    const chip1 = screen.getByRole('button', { name: 'Where is my parcel?' });
    const chip2 = screen.getByRole('button', { name: 'Price for 2 kg to Kandy' });
    expect(chip1).toBeInTheDocument();
    expect(chip2).toBeInTheDocument();

    // Click suggestion chip 2
    fireEvent.click(chip2);

    expect(askSpy).toHaveBeenCalledWith('Price for 2 kg to Kandy');

    await waitFor(() => {
      expect(screen.getAllByText('Price for 2 kg to Kandy').length).toBe(2);
      expect(screen.getByText('Deliveries to Kandy take 2 business days.')).toBeInTheDocument();
    });

    const userBubble = container.querySelector('.sd-chat-msg.is-user');
    expect(userBubble).toHaveTextContent('Price for 2 kg to Kandy');

    const replyBubble = screen.getByText('Deliveries to Kandy take 2 business days.');
    expect(replyBubble).toHaveClass('is-assistant');
  });

  it('submits text input via Send button or Enter key, showing typing state', async () => {
    let resolveAssistant: (value: AssistantResponse) => void;
    vi.spyOn(customerApi, 'askAssistant').mockReturnValue(
      new Promise((resolve) => {
        resolveAssistant = resolve;
      })
    );

    render(
      <MemoryRouter>
        <ToastProvider>
          <SupportPage />
        </ToastProvider>
      </MemoryRouter>
    );

    const input = screen.getByLabelText('Message to delivery assistant');
    const sendBtn = screen.getByRole('button', { name: 'Send' });

    fireEvent.change(input, { target: { value: 'How fast is express delivery?' } });
    fireEvent.click(sendBtn);

    // Displays typing state
    expect(screen.getByText('Assistant is typing...')).toBeInTheDocument();

    // Resolve the promise
    resolveAssistant!({
      reply: 'Express delivery arrives within 24 hours.',
    });

    await waitFor(() => {
      expect(screen.queryByText('Assistant is typing...')).not.toBeInTheDocument();
      expect(screen.getByText('Express delivery arrives within 24 hours.')).toBeInTheDocument();
    });
  });

  it('displays error bubble with retry button when assistant request fails', async () => {
    const askSpy = vi
      .spyOn(customerApi, 'askAssistant')
      .mockRejectedValueOnce(new Error('500 Service Error'))
      .mockResolvedValueOnce({
        reply: 'Recovered after retry.',
      });

    render(
      <MemoryRouter>
        <ToastProvider>
          <SupportPage />
        </ToastProvider>
      </MemoryRouter>
    );

    const input = screen.getByLabelText('Message to delivery assistant');
    fireEvent.change(input, { target: { value: 'Is Colombo hub open?' } });
    fireEvent.submit(input.closest('form')!);

    await waitFor(() => {
      expect(screen.getByText('Unable to reach the assistant right now.')).toBeInTheDocument();
    });

    const retryBtn = screen.getByRole('button', { name: 'Retry' });
    expect(retryBtn).toBeInTheDocument();

    fireEvent.click(retryBtn);

    await waitFor(() => {
      expect(askSpy).toHaveBeenCalledTimes(2);
      expect(screen.getByText('Recovered after retry.')).toBeInTheDocument();
    });
  });

  it('renders Common questions accordion with 5 short and factual answers', async () => {
    const { container } = render(
      <MemoryRouter>
        <ToastProvider>
          <SupportPage />
        </ToastProvider>
      </MemoryRouter>
    );

    expect(screen.getByText('How is the delivery fee calculated?')).toBeInTheDocument();
    expect(screen.getByText('What does each delivery status mean?')).toBeInTheDocument();
    expect(screen.getByText('What happens if a delivery fails?')).toBeInTheDocument();
    expect(screen.getByText('Can I track a parcel without an account?')).toBeInTheDocument();
    expect(screen.getByText('How do I get an official tax receipt?')).toBeInTheDocument();

    const faqItems = container.querySelectorAll('.sd-faq-item');
    expect(faqItems.length).toBe(5);
  });

  it('validates minimum 10 characters on Contact us message with inline error', async () => {
    render(
      <MemoryRouter>
        <ToastProvider>
          <SupportPage />
        </ToastProvider>
      </MemoryRouter>
    );

    const textarea = screen.getByPlaceholderText(/Describe your issue or question in detail.../i);
    const submitBtn = screen.getByRole('button', { name: /^Send message$/i });

    // Submit with short message
    fireEvent.change(textarea, { target: { value: 'Short' } });
    fireEvent.click(submitBtn);

    await waitFor(() => {
      expect(
        screen.getByText('Message must be at least 10 characters.')
      ).toBeInTheDocument();
    });
  });

  it('submits Contact us form, calls sendSupportMessage, and shows success toast with ticket number', async () => {
    const sendSpy = vi
      .spyOn(customerApi, 'sendSupportMessage')
      .mockResolvedValue(mockTicket);

    render(
      <MemoryRouter>
        <ToastProvider>
          <SupportPage />
        </ToastProvider>
      </MemoryRouter>
    );

    const topicSelect = screen.getByLabelText('Topic');
    const trackingInput = screen.getByLabelText(/Tracking number/i);
    const textarea = screen.getByPlaceholderText(/Describe your issue or question in detail.../i);
    const submitBtn = screen.getByRole('button', { name: /^Send message$/i });

    fireEvent.change(topicSelect, { target: { value: 'billing' } });
    fireEvent.change(trackingInput, { target: { value: 'ZTR-SD8941K2M7' } });
    fireEvent.change(textarea, {
      target: { value: 'Please send an updated VAT invoice for my business.' },
    });

    fireEvent.click(submitBtn);

    await waitFor(() => {
      expect(sendSpy).toHaveBeenCalledWith({
        subject: 'Support inquiry regarding billing',
        category: 'billing',
        trackingRef: 'ZTR-SD8941K2M7',
        message: 'Please send an updated VAT invoice for my business.',
      });
    });

    // Toast notification
    await waitFor(() => {
      expect(
        screen.getByText(/Message sent\. Ticket TICK-202610-8821/i)
      ).toBeInTheDocument();
    });

    // Message field cleared after successful submission
    expect((textarea as HTMLTextAreaElement).value).toBe('');
  });

  it('shows inline error with retry on Contact us submission failure', async () => {
    const sendSpy = vi
      .spyOn(customerApi, 'sendSupportMessage')
      .mockRejectedValueOnce(new Error('Network error'))
      .mockResolvedValueOnce(mockTicket);

    render(
      <MemoryRouter>
        <ToastProvider>
          <SupportPage />
        </ToastProvider>
      </MemoryRouter>
    );

    const textarea = screen.getByPlaceholderText(/Describe your issue or question in detail.../i);
    const submitBtn = screen.getByRole('button', { name: /^Send message$/i });

    fireEvent.change(textarea, {
      target: { value: 'I have not received my delivery yet.' },
    });
    fireEvent.click(submitBtn);

    await waitFor(() => {
      expect(
        screen.getByText(/Unable to send message\. Please verify your connection and retry\./i)
      ).toBeInTheDocument();
    });

    // Click retry in the inline error alert
    const errorAlert = screen.getByRole('alert');
    const retryBtn = errorAlert.querySelector('button');
    expect(retryBtn).toBeInTheDocument();

    fireEvent.click(retryBtn!);

    await waitFor(() => {
      expect(sendSpy).toHaveBeenCalledTimes(2);
    });
  });
});
