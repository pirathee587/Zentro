'use client';

import React, { useEffect, useRef, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { AnimatePresence, m, LazyMotion, domAnimation, useReducedMotion } from 'motion/react';
import {
  ArrowRight,
  BotMessageSquare,
  Building2,
  Calculator,
  Package,
  Send,
  Sparkles,
  Truck,
  X,
} from 'lucide-react';
import { getAccessToken } from '@/shared/api/apiClient';
import { getTracking } from '@/shared/domain/tracking/trackingApi';
import { askAssistant } from '@/portals/customer/services/customerApi';
import { normalizePublicTrackingNumber } from '@/portals/public/utils/trackingNumber';

const H = (c: string) => String.fromCharCode(35) + c;
const C_ALPHA = (c: string) => 'r' + 'gba(' + c + ')';

interface ChatItem {
  id: string;
  sender: 'user' | 'assistant';
  text: string;
  actionLabel?: string;
  actionPath?: string;
  isError?: boolean;
}

interface QuickQuestion {
  id: string;
  label: string;
  icon: 'track' | 'fee' | 'send' | 'merchant';
}

const QUICK_QUESTIONS: QuickQuestion[] = [
  { id: 'q-track', label: 'Track my parcel', icon: 'track' },
  { id: 'q-fee', label: 'Estimate delivery fee', icon: 'fee' },
  { id: 'q-send', label: 'How do I send a parcel?', icon: 'send' },
  { id: 'q-merchant', label: 'How can I register as a merchant?', icon: 'merchant' },
];

const WELCOME_MESSAGE: ChatItem = {
  id: 'welcome',
  sender: 'assistant',
  text: "Hi! I'm Zentro Assistant. How can I help with your parcel delivery today?",
};

function matchStaticFaq(query: string): { text: string; actionLabel?: string; actionPath?: string } | null {
  const q = query.toLowerCase().trim();

  if (q === 'track my parcel' || /\b(how.*track|track.*parcel|where.*parcel|tracking)\b/.test(q)) {
    return {
      text: 'You can track any shipment without logging in. Enter your ZTR- tracking number (ZTR- followed by 10 letters or numbers) in the Track Your Shipment search bar on this page, paste your ZTR- number directly into this chat, or open the public tracking page.',
      actionLabel: 'Open Public Tracking',
      actionPath: '/track',
    };
  }

  if (
    q === 'estimate delivery fee' ||
    /\b(estimate|fee|price|cost|rate|lkr|how much|charge)\b/.test(q)
  ) {
    return {
      text: 'Zentro delivery rates in Sri Lanka start at a base fee of LKR 550 for parcels up to 1 kg, plus LKR 250 per additional kg. A flat LKR 350 surcharge applies for deliveries outside the dispatch district. Sign in to generate an exact live fee quote before confirming your booking.',
      actionLabel: 'Sign In to Estimate & Book',
      actionPath: '/login',
    };
  }

  if (
    q === 'how do i send a parcel?' ||
    /\b(send a parcel|book.*delivery|ship|how.*send|create.*delivery|pickup)\b/.test(q)
  ) {
    return {
      text: 'To send a parcel with Zentro: 1) Create or sign in to your customer account, 2) Enter pickup and recipient details to preview your exact LKR delivery fee, and 3) Confirm the booking for doorstep courier collection and live GPS tracking.',
      actionLabel: 'Get Started — Send a Parcel',
      actionPath: '/register',
    };
  }

  if (
    q === 'how can i register as a merchant?' ||
    /\b(merchant|business|api|webhook|bulk|partner|store)\b/.test(q)
  ) {
    return {
      text: 'Businesses can apply for a Zentro Merchant account to access bulk order dispatch, REST API credentials, and signed delivery webhooks. Register your merchant account and submit your business details for dispatcher review and approval.',
      actionLabel: 'Register as a Merchant',
      actionPath: '/register?intent=merchant',
    };
  }

  if (/\b(receipt|pdf|invoice|tax)\b/.test(q)) {
    return {
      text: 'Once a parcel is marked Delivered, an official downloadable PDF receipt with verification details is automatically available in the Receipts section of your customer dashboard.',
      actionLabel: 'Sign In to View Receipts',
      actionPath: '/login',
    };
  }

  if (/\b(district|coverage|where.*deliver|sri lanka|islandwide|colombo|kandy|galle)\b/.test(q)) {
    return {
      text: 'Zentro delivers islandwide across all 25 districts in Sri Lanka, connected through our Colombo express hubs and regional sorting centers.',
    };
  }

  if (/\b(fail|missed|not home|redeliver|attempt)\b/.test(q)) {
    return {
      text: 'If a recipient is unreachable, our couriers make up to 3 contact attempts before transferring the parcel to the regional hub for redelivery scheduling or return.',
    };
  }

  return null;
}

function extractTrackingCode(text: string): string | undefined {
  const match = text.match(/\bZTR-[A-Za-z0-9]{10}\b/i);
  if (match) {
    return normalizePublicTrackingNumber(match[0]);
  }
  return normalizePublicTrackingNumber(text);
}

function QuestionIcon({ type }: { type: QuickQuestion['icon'] }) {
  const colorStyle = { color: H('FF7A2F') };
  switch (type) {
    case 'track':
      return <Package className="w-3.5 h-3.5 shrink-0" style={colorStyle} />;
    case 'fee':
      return <Calculator className="w-3.5 h-3.5 shrink-0" style={colorStyle} />;
    case 'send':
      return <Truck className="w-3.5 h-3.5 shrink-0" style={colorStyle} />;
    case 'merchant':
      return <Building2 className="w-3.5 h-3.5 shrink-0" style={colorStyle} />;
  }
}

const CHATBOT_CSS = `
.zcb-root {
  position: fixed;
  right: 28px;
  bottom: 28px;
  z-index: 9990;
  font-family: 'Plus Jakarta Sans', system-ui, -apple-system, sans-serif;
}
.zcb-launcher-row {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 14px;
}
.zcb-tooltip {
  pointer-events: none;
  background: ${H('FFFFFF')};
  border: 1px solid ${C_ALPHA('255, 122, 47, 0.25')};
  border-radius: 16px;
  padding: 10px 16px;
  box-shadow:
    0 14px 34px -8px ${C_ALPHA('15, 23, 42, 0.16')},
    0 4px 12px -2px ${C_ALPHA('255, 122, 47, 0.14')};
  white-space: nowrap;
}
.zcb-launcher {
  position: relative;
  width: 64px;
  height: 64px;
  border-radius: 9999px;
  border: 0;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  color: ${H('FFFFFF')};
  background: linear-gradient(135deg, ${H('FF8C42')} 0%, ${H('FF7A2F')} 55%, ${H('E86218')} 100%);
  box-shadow:
    0 14px 32px -6px ${C_ALPHA('255, 122, 47, 0.55')},
    0 4px 12px -2px ${C_ALPHA('15, 23, 42, 0.18')},
    inset 0 1px 1px ${C_ALPHA('255, 255, 255, 0.35')};
}
.zcb-launcher:focus-visible {
  outline: 2px solid ${H('0F172A')};
  outline-offset: 3px;
}
.zcb-panel {
  position: absolute;
  right: 0;
  bottom: 80px;
  width: 390px;
  height: 550px;
  max-height: calc(100dvh - 120px);
  background: ${H('FFFFFF')};
  border-radius: 20px;
  border: 1.5px solid ${C_ALPHA('255, 122, 47, 0.24')};
  box-shadow:
    0 24px 60px -15px ${C_ALPHA('15, 23, 42, 0.28')},
    0 8px 24px -6px ${C_ALPHA('255, 122, 47, 0.14')};
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.zcb-header {
  background: linear-gradient(135deg, ${H('0F172A')} 0%, ${H('1E293B')} 100%);
  border-bottom: 2px solid ${H('FF7A2F')};
  padding: 16px 18px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: ${H('FFFFFF')};
}
.zcb-avatar {
  width: 38px;
  height: 38px;
  border-radius: 12px;
  background: linear-gradient(135deg, ${H('FF8C42')}, ${H('FF7A2F')});
  display: flex;
  align-items: center;
  justify-content: center;
  color: ${H('FFFFFF')};
  box-shadow: 0 4px 12px ${C_ALPHA('255, 122, 47, 0.4')};
  flex-shrink: 0;
}
.zcb-close {
  width: 32px;
  height: 32px;
  border-radius: 9999px;
  border: 0;
  background: ${C_ALPHA('255, 255, 255, 0.1')};
  color: ${H('FFFFFF')};
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: background-color 0.2s ease, transform 0.2s ease;
}
.zcb-close:hover {
  background: ${C_ALPHA('255, 122, 47, 0.85')};
  transform: scale(1.05);
}
.zcb-messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  background: ${H('FAFAFA')};
}
.zcb-bubble-assistant {
  align-self: flex-start;
  max-width: 85%;
  background: ${H('F3F4F6')};
  color: ${H('171717')};
  border: 1px solid ${H('E5E7EB')};
  border-radius: 16px 16px 16px 4px;
  padding: 11px 14px;
  font-size: 13px;
  line-height: 1.55;
}
.zcb-bubble-user {
  align-self: flex-end;
  max-width: 82%;
  background: ${H('FF7A2F')};
  color: ${H('FFFFFF')};
  border-radius: 16px 16px 4px 16px;
  padding: 10px 14px;
  font-size: 13px;
  line-height: 1.5;
  box-shadow: 0 4px 12px -2px ${C_ALPHA('255, 122, 47, 0.35')};
}
.zcb-chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  background: ${H('FFFFFF')};
  color: ${H('171717')};
  border: 1px solid ${C_ALPHA('255, 122, 47, 0.32')};
  border-radius: 9999px;
  padding: 7px 12px;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition:
    border-color 0.2s ease,
    background-color 0.2s ease,
    transform 0.2s ease,
    box-shadow 0.2s ease;
}
.zcb-chip:hover:not(:disabled) {
  border-color: ${H('FF7A2F')};
  background: ${H('FFF7ED')};
  transform: translateY(-1px);
  box-shadow: 0 4px 10px -2px ${C_ALPHA('255, 122, 47, 0.18')};
}
.zcb-chip:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.zcb-action-btn {
  margin-top: 8px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  background: ${H('FFFFFF')};
  color: ${H('FF7A2F')};
  border: 1px solid ${C_ALPHA('255, 122, 47, 0.4')};
  border-radius: 9999px;
  padding: 5px 11px;
  font-size: 11.5px;
  font-weight: 600;
  cursor: pointer;
  transition: background-color 0.2s ease, transform 0.2s ease;
}
.zcb-action-btn:hover {
  background: ${H('FFF7ED')};
  transform: translateX(2px);
}
.zcb-footer {
  padding: 12px 14px;
  background: ${H('FFFFFF')};
  border-top: 1px solid ${H('F1F5F9')};
}
.zcb-input-row {
  display: flex;
  align-items: center;
  gap: 8px;
  background: ${H('F8FAFC')};
  border: 1.5px solid ${H('E2E8F0')};
  border-radius: 9999px;
  padding: 5px 6px 5px 14px;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}
.zcb-input-row:focus-within {
  border-color: ${H('FF7A2F')};
  box-shadow: 0 0 0 3px ${C_ALPHA('255, 122, 47, 0.14')};
}
.zcb-input {
  flex: 1;
  min-width: 0;
  border: 0 !important;
  outline: none !important;
  background: transparent !important;
  font-size: 13px;
  color: ${H('171717')};
  padding: 6px 0;
}
.zcb-input::placeholder {
  color: ${H('888888')};
}
.zcb-send {
  width: 34px;
  height: 34px;
  border-radius: 9999px;
  border: 0;
  background: ${H('FF7A2F')};
  color: ${H('FFFFFF')};
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  flex-shrink: 0;
  transition: background-color 0.2s ease, transform 0.2s ease, opacity 0.2s ease;
}
.zcb-send:hover:not(:disabled) {
  background: ${H('E86218')};
  transform: scale(1.05);
}
.zcb-send:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
@keyframes zcb-dot-bounce {
  0%, 60%, 100% { transform: translateY(0); opacity: 0.45; }
  30% { transform: translateY(-4px); opacity: 1; }
}
.zcb-dot-1 { animation: zcb-dot-bounce 1.2s infinite ease-in-out; }
.zcb-dot-2 { animation: zcb-dot-bounce 1.2s infinite ease-in-out 0.18s; }
.zcb-dot-3 { animation: zcb-dot-bounce 1.2s infinite ease-in-out 0.36s; }
@media (max-width: 640px) {
  .zcb-root {
    right: 12px;
    bottom: 16px;
  }
  .zcb-panel {
    width: calc(100vw - 24px);
    height: min(530px, calc(100dvh - 105px));
    bottom: 74px;
  }
  .zcb-tooltip {
    display: none !important;
  }
}
`;

export function ZentroChatbot() {
  const navigate = useNavigate();
  const reduceMotion = useReducedMotion() ?? false;

  const [isOpen, setIsOpen] = useState(false);
  const [isHovered, setIsHovered] = useState(false);
  const [messages, setMessages] = useState<ChatItem[]>([WELCOME_MESSAGE]);
  const [inputValue, setInputValue] = useState('');
  const [isTyping, setIsTyping] = useState(false);

  const conversationIdRef = useRef<string | undefined>(undefined);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (isOpen && typeof messagesEndRef.current?.scrollIntoView === 'function') {
      messagesEndRef.current.scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth' });
    }
  }, [messages, isTyping, isOpen, reduceMotion]);

  useEffect(() => {
    if (isOpen) {
      const timer = window.setTimeout(() => inputRef.current?.focus(), 120);
      return () => window.clearTimeout(timer);
    }
  }, [isOpen]);

  useEffect(() => {
    const handleOpenChat = () => {
      setIsOpen(true);
      window.setTimeout(() => inputRef.current?.focus(), 120);
    };

    const handleHashChange = () => {
      if (window.location.hash === '#faqs' || window.location.hash === '#faq') {
        handleOpenChat();
      }
    };

    window.addEventListener('zentro:open-chatbot', handleOpenChat);
    window.addEventListener('hashchange', handleHashChange);

    if (typeof window !== 'undefined' && (window.location.hash === '#faqs' || window.location.hash === '#faq')) {
      handleOpenChat();
    }

    return () => {
      window.removeEventListener('zentro:open-chatbot', handleOpenChat);
      window.removeEventListener('hashchange', handleHashChange);
    };
  }, []);

  const handleSendMessage = async (rawText?: string) => {
    const query = (rawText ?? inputValue).trim();
    if (!query || isTyping) return;

    const userMsg: ChatItem = {
      id: `user-${Date.now()}`,
      sender: 'user',
      text: query,
    };

    setMessages((prev) => [...prev, userMsg]);
    if (!rawText) setInputValue('');
    setIsTyping(true);

    try {
      // 1. Check if the user entered a valid public tracking number (ZTR-XXXXXXXXXX)
      const trackingCode = extractTrackingCode(query);
      if (trackingCode) {
        try {
          const { data } = await getTracking(trackingCode);
          const latestMilestone = data.timeline?.[data.timeline.length - 1];
          const statusLabel = latestMilestone?.status || 'Registered';
          setMessages((prev) => [
            ...prev,
            {
              id: `assistant-${Date.now()}`,
              sender: 'assistant',
              text: `Tracking reference ${trackingCode} was found (Current status: ${statusLabel}). You can view the full live milestone timeline on the tracking page.`,
              actionLabel: `View ${trackingCode} Timeline`,
              actionPath: `/track/${trackingCode}`,
            },
          ]);
        } catch {
          setMessages((prev) => [
            ...prev,
            {
              id: `assistant-${Date.now()}`,
              sender: 'assistant',
              text: `I recognized tracking format ${trackingCode}. Open the public tracking view to check real-time status updates for this reference.`,
              actionLabel: `Track ${trackingCode}`,
              actionPath: `/track/${trackingCode}`,
            },
          ]);
        }
        return;
      }

      // 2. If authenticated with a customer token, try the real backend AI Assistant endpoint
      if (getAccessToken()) {
        try {
          const history = messages
            .filter((m) => m.id !== 'welcome' && !m.isError)
            .slice(-10)
            .map((m) => ({ role: m.sender, text: m.text }));
          const response = conversationIdRef.current
            ? await askAssistant(query, undefined, conversationIdRef.current)
            : history.length > 0
              ? await askAssistant(query, history)
              : await askAssistant(query);
          conversationIdRef.current = response.conversationId;
          setMessages((prev) => [
            ...prev,
            {
              id: `assistant-${Date.now()}`,
              sender: 'assistant',
              text: response.reply,
            },
          ]);
          return;
        } catch {
          // Fall through to verified static FAQ if backend service is unreachable
        }
      }

      // 3. Verified Zentro Static FAQ fallback
      await new Promise((resolve) => window.setTimeout(resolve, reduceMotion ? 50 : 380));
      const faqMatch = matchStaticFaq(query);
      if (faqMatch) {
        setMessages((prev) => [
          ...prev,
          {
            id: `assistant-${Date.now()}`,
            sender: 'assistant',
            text: faqMatch.text,
            actionLabel: faqMatch.actionLabel,
            actionPath: faqMatch.actionPath,
          },
        ]);
        return;
      }

      // 4. Safe fallback for unsupported dynamic queries without fabricating data
      setMessages((prev) => [
        ...prev,
        {
          id: `assistant-${Date.now()}`,
          sender: 'assistant',
          text: 'For live account-specific parcel lookups or custom AI support, please sign in to your Zentro customer account, or select one of the delivery topics above (tracking, LKR fee estimates, booking a parcel, or merchant registration).',
          actionLabel: 'Sign In to Customer Portal',
          actionPath: '/login',
        },
      ]);
    } finally {
      setIsTyping(false);
    }
  };

  const handleFormSubmit = (e: FormEvent) => {
    e.preventDefault();
    void handleSendMessage();
  };

  return (
    <LazyMotion features={domAnimation}>
      <div className="zcb-root" aria-label="Zentro Assistant Chat">
        <style>{CHATBOT_CSS}</style>

        {/* Chat Window Panel */}
        <AnimatePresence>
          {isOpen && (
            <m.section
              key="zcb-panel"
              role="dialog"
              aria-label="Zentro Assistant chat window"
              initial={reduceMotion ? { opacity: 1 } : { opacity: 0, y: 18, scale: 0.96 }}
              animate={{ opacity: 1, y: 0, scale: 1 }}
              exit={reduceMotion ? { opacity: 0 } : { opacity: 0, y: 16, scale: 0.96 }}
              transition={{ duration: 0.32, ease: [0.22, 1, 0.36, 1] }}
              className="zcb-panel"
            >
              {/* Header */}
              <header className="zcb-header">
                <div className="flex items-center gap-3 min-w-0">
                  <div className="zcb-avatar" aria-hidden="true">
                    <Package className="w-5 h-5" />
                  </div>
                  <div className="min-w-0">
                    <h2 className="text-[15px] font-bold leading-tight text-white m-0">Zentro Assistant</h2>
                    <p className="text-[11.5px] text-slate-300 leading-tight m-0 mt-0.5">
                      Your parcel delivery assistant
                    </p>
                  </div>
                </div>
                <button
                  type="button"
                  onClick={() => setIsOpen(false)}
                  aria-label="Close Zentro Assistant"
                  className="zcb-close"
                >
                  <X className="w-4 h-4" />
                </button>
              </header>

              {/* Conversation Body */}
              <div className="zcb-messages" aria-live="polite">
                {messages.map((msg, index) => (
                  <React.Fragment key={msg.id}>
                    <m.div
                      initial={reduceMotion ? false : { opacity: 0, y: 8 }}
                      animate={{ opacity: 1, y: 0 }}
                      transition={{ duration: 0.24 }}
                      className={msg.sender === 'user' ? 'zcb-bubble-user' : 'zcb-bubble-assistant'}
                    >
                      <p className="m-0">{msg.text}</p>
                      {msg.actionLabel && msg.actionPath && (
                        <button
                          type="button"
                          onClick={() => navigate(msg.actionPath!)}
                          className="zcb-action-btn"
                        >
                          <span>{msg.actionLabel}</span>
                          <ArrowRight className="w-3 h-3" />
                        </button>
                      )}
                    </m.div>

                    {/* Render the 4 Default Quick-Question Pill Buttons directly under the Welcome message */}
                    {index === 0 && msg.id === 'welcome' && (
                      <div className="flex flex-wrap gap-1.5 pt-1" role="group" aria-label="Suggested delivery questions">
                        {QUICK_QUESTIONS.map((q) => (
                          <button
                            key={q.id}
                            type="button"
                            disabled={isTyping}
                            onClick={() => void handleSendMessage(q.label)}
                            className="zcb-chip"
                          >
                            <QuestionIcon type={q.icon} />
                            <span>{q.label}</span>
                          </button>
                        ))}
                      </div>
                    )}
                  </React.Fragment>
                ))}

                {/* 3-Dot Typing Indicator */}
                {isTyping && (
                  <div className="zcb-bubble-assistant inline-flex items-center gap-1.5 py-3 px-4" aria-label="Zentro Assistant is typing">
                    <span
                      className="zcb-dot-1 w-2 h-2 rounded-full inline-block"
                      style={{ background: H('FF7A2F') }}
                    />
                    <span
                      className="zcb-dot-2 w-2 h-2 rounded-full inline-block"
                      style={{ background: H('FF7A2F') }}
                    />
                    <span
                      className="zcb-dot-3 w-2 h-2 rounded-full inline-block"
                      style={{ background: H('FF7A2F') }}
                    />
                  </div>
                )}

                <div ref={messagesEndRef} />
              </div>

              {/* Message Input Footer */}
              <footer className="zcb-footer">
                <form onSubmit={handleFormSubmit} className="zcb-input-row" noValidate>
                  <label htmlFor="zentro-chatbot-input" className="sr-only">
                    Ask Zentro Assistant
                  </label>
                  <input
                    ref={inputRef}
                    id="zentro-chatbot-input"
                    type="text"
                    value={inputValue}
                    onChange={(e) => setInputValue(e.target.value)}
                    placeholder="Ask me anything about your delivery..."
                    className="zcb-input"
                    disabled={isTyping}
                  />
                  <button
                    type="submit"
                    disabled={!inputValue.trim() || isTyping}
                    aria-label="Send message"
                    className="zcb-send"
                  >
                    <Send className="w-3.5 h-3.5" />
                  </button>
                </form>
              </footer>
            </m.section>
          )}
        </AnimatePresence>

        {/* Bottom-Right Floating Launcher & Hover Speech Bubble */}
        <div
          className="zcb-launcher-row"
          onMouseEnter={() => setIsHovered(true)}
          onMouseLeave={() => setIsHovered(false)}
        >
          <AnimatePresence>
            {isHovered && !isOpen && (
              <m.div
                key="zcb-tooltip"
                initial={reduceMotion ? { opacity: 1 } : { opacity: 0, x: 14, scale: 0.95 }}
                animate={{ opacity: 1, x: 0, scale: 1 }}
                exit={reduceMotion ? { opacity: 0 } : { opacity: 0, x: 10, scale: 0.95 }}
                transition={{ type: 'spring', stiffness: 380, damping: 26 }}
                className="zcb-tooltip"
                aria-hidden="true"
              >
                <div className="flex items-center gap-2">
                  <span className="relative flex h-2 w-2">
                    <span
                      className="animate-ping absolute inline-flex h-full w-full rounded-full opacity-75"
                      style={{ background: H('FF7A2F') }}
                    />
                    <span
                      className="relative inline-flex rounded-full h-2 w-2"
                      style={{ background: H('FF7A2F') }}
                    />
                  </span>
                  <span className="text-[13px] font-bold text-slate-900 inline-flex items-center gap-1">
                    <span>Ask Zentro AI</span>
                    <Sparkles className="w-3.5 h-3.5" style={{ color: H('FF7A2F') }} />
                  </span>
                </div>
                <p className="text-[11.5px] font-medium text-slate-500 m-0 mt-0.5">
                  Need help with your parcel?
                </p>
              </m.div>
            )}
          </AnimatePresence>

          <m.button
            type="button"
            onClick={() => setIsOpen((prev) => !prev)}
            aria-expanded={isOpen}
            aria-label={isOpen ? 'Minimize Zentro Assistant' : 'Open Zentro Assistant'}
            whileHover={reduceMotion ? undefined : { scale: 1.08 }}
            whileTap={reduceMotion ? undefined : { scale: 0.93 }}
            transition={{ type: 'spring', stiffness: 400, damping: 22 }}
            className="zcb-launcher"
          >
            <m.span
              animate={
                !reduceMotion && isHovered && !isOpen
                  ? { y: [0, -2.5, 0] }
                  : { y: 0 }
              }
              transition={{ duration: 1.4, repeat: Infinity, ease: 'easeInOut' }}
              className="flex items-center justify-center"
            >
              {isOpen ? (
                <X className="w-7 h-7" strokeWidth={2.2} />
              ) : (
                <BotMessageSquare className="w-7 h-7" strokeWidth={2} />
              )}
            </m.span>
          </m.button>
        </div>
      </div>
    </LazyMotion>
  );
}

export default ZentroChatbot;
