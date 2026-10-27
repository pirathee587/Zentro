import React, { useState, useRef, useEffect } from 'react';
import {
  Button as BaseButton,
  Input as BaseInput,
  Select as BaseSelect,
  Textarea as BaseTextarea,
} from '@/shared/ui';
import { Card, CardHeader, CardTitle, CardContent } from '../components/Card';
import { Button } from '../components/Button';
import { Field } from '../components/Field';
import { useToast } from '../components/Toast';
import {
  SendIcon,
  HelpCircleIcon,
  MailIcon,
  CircleAlertIcon,
} from '../components/icons';
import {
  askAssistant,
  sendSupportMessage,
  type SupportMessagePayload,
} from '../services/customerApi';

interface ChatMessage {
  id: string;
  sender: 'user' | 'assistant';
  text: string;
  isError?: boolean;
}

const FAQ_ITEMS = [
  {
    id: 'faq-1',
    question: 'How is the delivery fee calculated?',
    answer:
      'Delivery rates start at a base fee of LKR 550 for up to 1 kg. Additional weight is charged at LKR 250 per kg. A flat distance surcharge of LKR 350 applies for deliveries outside your dispatch district.',
  },
  {
    id: 'faq-2',
    question: 'What does each delivery status mean?',
    answer:
      'Created means the booking is confirmed and awaiting courier collection. Picked up indicates the courier has collected the package. In transit means dispatch between sorting hubs. Out for delivery indicates final doorstep arrival today. Delivered confirms recipient handoff.',
  },
  {
    id: 'faq-3',
    question: 'What happens if a delivery fails?',
    answer:
      'If the recipient is unreachable or the address is incorrect, our couriers make 3 contact attempts before transferring the parcel to the regional hub for redelivery scheduling or return.',
  },
  {
    id: 'faq-4',
    question: 'Can I track a parcel without an account?',
    answer:
      'Yes. Anyone with a valid tracking reference starting with ZTR- can view live milestone updates on the Public Tracking page at /track without logging in.',
  },
  {
    id: 'faq-5',
    question: 'How do I get an official tax receipt?',
    answer:
      'Once a parcel is marked Delivered, official receipts with itemized tax and payment confirmations are generated in the Receipts section and can be printed or saved as PDF.',
  },
];

export const SupportPage: React.FC = () => {
  const { showToast } = useToast();

  // Assistant Chat State
  const [messages, setMessages] = useState<ChatMessage[]>([
    {
      id: 'welcome',
      sender: 'assistant',
      text: 'Hello! I am your SwiftDrop Delivery Assistant. How can I help you today?',
    },
  ]);
  const [inputQuestion, setInputQuestion] = useState('');
  const [isTyping, setIsTyping] = useState(false);
  const [lastFailedQuestion, setLastFailedQuestion] = useState<string | null>(null);

  const chatBottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (typeof chatBottomRef.current?.scrollIntoView === 'function') {
      chatBottomRef.current.scrollIntoView({ behavior: 'smooth' });
    }
  }, [messages, isTyping]);

  const handleSendMessage = async (textToSend?: string) => {
    const query = (textToSend || inputQuestion).trim();
    if (!query || isTyping) return;

    const userMsgId = `user-${Date.now()}`;
    setMessages((prev) => [...prev, { id: userMsgId, sender: 'user', text: query }]);
    if (!textToSend) setInputQuestion('');
    setIsTyping(true);
    setLastFailedQuestion(null);

    try {
      // The assistant UI must not compute prices or statuses itself
      const response = await askAssistant(query);
      const assistantMsgId = `assistant-${Date.now()}`;
      setMessages((prev) => [
        ...prev,
        { id: assistantMsgId, sender: 'assistant', text: response.reply },
      ]);
    } catch {
      setLastFailedQuestion(query);
      const errorMsgId = `error-${Date.now()}`;
      setMessages((prev) => [
        ...prev,
        {
          id: errorMsgId,
          sender: 'assistant',
          text: 'Unable to reach the assistant right now.',
          isError: true,
        },
      ]);
    } finally {
      setIsTyping(false);
    }
  };

  const handleRetryAssistant = () => {
    if (lastFailedQuestion) {
      void handleSendMessage(lastFailedQuestion);
    }
  };

  const handleChipClick = (chipText: string) => {
    void handleSendMessage(chipText);
  };

  // Contact Us Form State
  const [topic, setTopic] = useState<'delivery' | 'billing' | 'account' | 'general'>('delivery');
  const [trackingNumber, setTrackingNumber] = useState('');
  const [contactMessage, setContactMessage] = useState('');
  const [contactError, setContactError] = useState('');
  const [submitError, setSubmitError] = useState('');
  const [isSending, setIsSending] = useState(false);

  const handleContactSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setContactError('');
    setSubmitError('');

    const trimmedMsg = contactMessage.trim();
    if (trimmedMsg.length < 10) {
      setContactError('Message must be at least 10 characters.');
      return;
    }

    setIsSending(true);

    const payload: SupportMessagePayload = {
      subject: `Support inquiry regarding ${topic}`,
      category: topic,
      trackingRef: trackingNumber.trim() || undefined,
      message: trimmedMsg,
    };

    try {
      const ticket = await sendSupportMessage(payload);
      const ticketId = ticket.ticketNumber || ticket.id;

      if (showToast) {
        showToast({
          title: 'Message sent',
          message: `Message sent. Ticket ${ticketId}`,
          type: 'success',
        });
      }

      // Reset form
      setContactMessage('');
      setTrackingNumber('');
      setContactError('');
    } catch {
      setSubmitError('Unable to send message. Please verify your connection and retry.');
    } finally {
      setIsSending(false);
    }
  };

  return (
    <div>
      {/* Header */}
      <div className="sd-page-header">
        <h1 className="sd-page-title">Support</h1>
        <p className="sd-page-subtitle">
          Live delivery assistance, answers to common inquiries, and support ticket dispatch
        </p>
      </div>

      <div className="sd-grid-12">
        {/* Row 1, Left: Delivery Assistant Card (span 7, dark --panel card) */}
        <Card span={7} variant="panel">
        <CardHeader>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <span
              style={{
                width: '8px',
                height: '8px',
                borderRadius: '50%',
                background: 'var(--y)',
              }}
              aria-hidden="true"
            />
            <CardTitle style={{ color: 'var(--onDark)' }}>Delivery assistant</CardTitle>
          </div>
          <span style={{ fontSize: '12px', color: 'var(--mute)' }}>
            AI Powered
          </span>
        </CardHeader>

        <CardContent>
          {/* Scrollable chat area (role="log", aria-live="polite", ~290px high) */}
          <div
            className="sd-chat-area"
            role="log"
            aria-live="polite"
            aria-label="Delivery assistant conversation"
          >
            {messages.map((msg) => {
              if (msg.isError) {
                return (
                  <div key={msg.id} className="sd-chat-msg is-error" role="alert">
                    <span>{msg.text}</span>
                    <BaseButton
                      type="button"
                      onClick={handleRetryAssistant}
                      className="sd-btn sd-btn-outline sd-btn-small"
                      style={{
                        padding: '2px 8px',
                        fontSize: '11px',
                        borderColor: 'var(--line)',
                        color: 'var(--onDark)',
                      }}
                    >
                      Retry
                    </BaseButton>
                  </div>
                );
              }

              return (
                <div
                  key={msg.id}
                  className={`sd-chat-msg ${msg.sender === 'user' ? 'is-user' : 'is-assistant'}`}
                >
                  {msg.text}
                </div>
              );
            })}

            {isTyping && (
              <div className="sd-chat-msg is-typing" aria-label="Assistant is typing">
                Assistant is typing...
              </div>
            )}
            <div ref={chatBottomRef} />
          </div>

          {/* Two suggestion chips */}
          <div
            className="sd-chat-suggestion-chips"
            role="group"
            aria-label="Quick questions"
          >
            <BaseButton
              type="button"
              className="sd-chat-chip"
              onClick={() => handleChipClick('Where is my parcel?')}
            >
              Where is my parcel?
            </BaseButton>
            <BaseButton
              type="button"
              className="sd-chat-chip"
              onClick={() => handleChipClick('Price for 2 kg to Kandy')}
            >
              Price for 2 kg to Kandy
            </BaseButton>
          </div>

          {/* Chat input bar */}
          <form
            onSubmit={(e) => {
              e.preventDefault();
              void handleSendMessage();
            }}
            className="sd-chat-input-bar"
          >
            <BaseInput
              type="text"
              className="sd-chat-input"
              placeholder="Ask about deliveries, prices, or tracking..."
              aria-label="Message to delivery assistant"
              value={inputQuestion}
              onChange={(e) => setInputQuestion(e.target.value)}
              disabled={isTyping}
            />
            <Button
              type="submit"
              variant="accent"
              icon={<SendIcon size={16} />}
              disabled={isTyping || !inputQuestion.trim()}
              aria-label="Send"
            >
              Send
            </Button>
          </form>
        </CardContent>
      </Card>

      {/* Row 1, Right: Common questions card (span 5) */}
      <Card span={5}>
        <CardHeader>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <HelpCircleIcon size={18} />
            <CardTitle>Common questions</CardTitle>
          </div>
        </CardHeader>
        <CardContent>
          <div className="sd-faq-list">
            {FAQ_ITEMS.map((item) => (
              <details key={item.id} className="sd-faq-item">
                <summary className="sd-faq-summary">
                  <span>{item.question}</span>
                </summary>
                <div className="sd-faq-content">{item.answer}</div>
              </details>
            ))}
          </div>
        </CardContent>
      </Card>

      {/* Row 2: "Contact us" card (full width, span 12) */}
      <Card span={12}>
        <CardHeader>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <MailIcon size={18} />
            <CardTitle>Contact us</CardTitle>
          </div>
        </CardHeader>

        <CardContent>
          <form onSubmit={handleContactSubmit} noValidate>
            <div className="sd-form-grid" style={{ marginBottom: '14px' }}>
              {/* Topic Select */}
              <div className="sd-field">
                <label className="sd-label" htmlFor="contact-topic-select">
                  Topic
                </label>
                <BaseSelect
                  id="contact-topic-select"
                  className="sd-input sd-select"
                  value={topic}
                  onChange={(e) =>
                    setTopic(e.target.value as 'delivery' | 'billing' | 'account' | 'general')
                  }
                >
                  <option value="delivery">Delivery issue or inquiry</option>
                  <option value="billing">Billing & Payment receipt</option>
                  <option value="account">Account & Business partnership</option>
                  <option value="general">General feedback & Questions</option>
                </BaseSelect>
              </div>

              {/* Optional Tracking Number */}
              <Field
                id="contact-tracking-ref"
                label="Tracking number (optional)"
                placeholder="e.g. ZTR-SD8941K2M7"
                value={trackingNumber}
                onChange={(e) => setTrackingNumber(e.target.value)}
              />
            </div>

            {/* Message Area (min 10 chars) */}
            <div className="sd-field" style={{ marginBottom: '16px' }}>
              <label className="sd-label" htmlFor="contact-message-input">
                Message <span style={{ color: 'var(--mute)', fontWeight: '400' }}>(min. 10 characters)</span>
              </label>
              <BaseTextarea
                id="contact-message-input"
                className={`sd-input ${contactError ? 'is-error' : ''}`}
                rows={4}
                placeholder="Describe your issue or question in detail..."
                value={contactMessage}
                onChange={(e) => {
                  setContactMessage(e.target.value);
                  if (contactError) setContactError('');
                }}
                aria-invalid={Boolean(contactError)}
                aria-describedby={contactError ? 'contact-message-error' : undefined}
                style={{ resize: 'vertical' }}
              />
              {contactError && (
                <div
                  id="contact-message-error"
                  role="alert"
                  className="sd-field-error"
                  style={{ marginTop: '6px' }}
                >
                  {contactError}
                </div>
              )}
            </div>

            {/* Failure state with Retry */}
            {submitError && (
              <div
                role="alert"
                className="sd-field-error"
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: '8px',
                  marginBottom: '16px',
                  padding: '10px 14px',
                  borderRadius: '10px',
                  background: 'var(--card)',
                  border: '1px solid var(--err)',
                  color: 'var(--err)',
                }}
              >
                <CircleAlertIcon size={16} />
                <span style={{ flex: 1 }}>{submitError}</span>
                <Button
                  type="button"
                  variant="outline"
                  size="small"
                  onClick={handleContactSubmit}
                >
                  Retry
                </Button>
              </div>
            )}

            {/* Submit Button */}
            <div style={{ display: 'flex', justifyContent: 'flex-start' }}>
              <Button
                type="submit"
                variant="accent"
                disabled={isSending}
                icon={<SendIcon size={16} />}
              >
                {isSending ? 'Sending message...' : 'Send message'}
              </Button>
            </div>
          </form>
        </CardContent>
      </Card>
      </div>
    </div>
  );
};

export default SupportPage;
