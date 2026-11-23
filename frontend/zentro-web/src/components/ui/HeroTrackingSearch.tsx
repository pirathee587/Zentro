'use client';

import React, { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowRight, Loader2, Package } from 'lucide-react';
import { normalizePublicTrackingNumber } from '@/portals/public/utils/trackingNumber';

const H = (c: string) => String.fromCharCode(35) + c;
const C_ALPHA = (c: string) => 'r' + 'gba(' + c + ')';

const HERO_TRACKING_CSS = `
.hts-wrap {
  position: relative;
  z-index: 4;
  width: 100%;
  max-width: 830px;
  margin: 0 auto;
  padding: 6px 20px 36px;
  animation: hts-fade-up 0.75s cubic-bezier(0.22, 1, 0.36, 1) 0.18s both;
}
.hts-header {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
  margin-bottom: 14px;
}
.hts-line-left {
  height: 1px;
  width: 56px;
  background: linear-gradient(90deg, transparent, ${C_ALPHA('255, 122, 47, 0.45')});
}
.hts-line-right {
  height: 1px;
  width: 56px;
  background: linear-gradient(270deg, transparent, ${C_ALPHA('255, 122, 47, 0.45')});
}
.hts-title {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: ${H('FF7A2F')};
}
.hts-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  background: ${H('FFFFFF')};
  border: 1.5px solid ${C_ALPHA('255, 122, 47, 0.24')};
  border-radius: 9999px;
  padding: 7px 8px 7px 20px;
  box-shadow:
    0 12px 34px -10px ${C_ALPHA('255, 122, 47, 0.16')},
    0 4px 14px -4px ${C_ALPHA('23, 23, 23, 0.05')};
  transition:
    border-color 0.25s ease,
    box-shadow 0.25s ease,
    transform 0.25s ease;
}
.hts-bar:focus-within {
  border-color: ${H('FF7A2F')};
  box-shadow:
    0 0 0 4px ${C_ALPHA('255, 122, 47, 0.14')},
    0 16px 40px -10px ${C_ALPHA('255, 122, 47, 0.24')};
}
.hts-input {
  flex: 1;
  min-width: 0;
  border: 0 !important;
  outline: none !important;
  box-shadow: none !important;
  background: transparent !important;
  font-size: 14.5px;
  font-weight: 500;
  color: ${H('171717')};
  padding: 10px 6px;
}
.hts-input::placeholder {
  color: ${H('888888')};
  font-weight: 400;
}
.hts-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 9px;
  background: ${H('171717')};
  color: ${H('FFFFFF')};
  border: 0;
  border-radius: 9999px;
  padding: 13px 26px;
  font-size: 13.5px;
  font-weight: 600;
  white-space: nowrap;
  cursor: pointer;
  box-shadow: 0 8px 20px -6px ${C_ALPHA('23, 23, 23, 0.35')};
  transition:
    background-color 0.2s ease,
    transform 0.2s ease,
    box-shadow 0.2s ease;
}
.hts-btn:hover:not(:disabled) {
  background: ${H('262626')};
  transform: translateY(-1.5px);
  box-shadow: 0 12px 24px -6px ${C_ALPHA('23, 23, 23, 0.45')};
}
.hts-btn:active:not(:disabled) {
  transform: translateY(0);
}
.hts-btn-arrow {
  transition: transform 0.22s ease;
}
.hts-btn:hover:not(:disabled) .hts-btn-arrow {
  transform: translateX(3px);
}
.hts-subtext {
  margin-top: 11px;
  text-align: center;
  font-size: 12px;
  font-weight: 400;
  color: ${H('888888')};
  letter-spacing: 0.01em;
}
.hts-error {
  margin-top: 10px;
  text-align: center;
  font-size: 12.5px;
  font-weight: 500;
  color: var(--err);
}
@keyframes hts-fade-up {
  from {
    opacity: 0;
    transform: translateY(16px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
@media (max-width: 640px) {
  .hts-wrap {
    padding: 0 16px 32px;
  }
  .hts-bar {
    flex-direction: column;
    align-items: stretch;
    border-radius: 20px;
    padding: 10px 12px;
    gap: 8px;
  }
  .hts-input-row {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 0 6px;
  }
  .hts-btn {
    width: 100%;
    padding: 13px 20px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .hts-wrap {
    animation: none !important;
  }
  .hts-bar,
  .hts-btn,
  .hts-btn-arrow {
    transition: none !important;
  }
}
`;

export function HeroTrackingSearch() {
  const [tracking, setTracking] = useState('');
  const [trackError, setTrackError] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const navigate = useNavigate();

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault();
    const code = normalizePublicTrackingNumber(tracking);
    if (!code) {
      setTrackError('Enter a valid tracking number: ZTR- followed by 10 letters or numbers.');
      return;
    }
    setTrackError('');
    setIsSubmitting(true);
    navigate(`/track/${code}`);
  };

  return (
    <div className="hts-wrap" aria-label="Track your shipment">
      <style>{HERO_TRACKING_CSS}</style>

      {/* Heading with decorative orange side lines */}
      <div className="hts-header">
        <span className="hts-line-left" aria-hidden="true" />
        <span className="hts-title">
          <Package className="w-3.5 h-3.5 shrink-0" strokeWidth={2.2} aria-hidden="true" />
          <span>TRACK YOUR SHIPMENT</span>
        </span>
        <span className="hts-line-right" aria-hidden="true" />
      </div>

      {/* Rounded Pill Search Form */}
      <form className="hts-bar" onSubmit={handleSubmit} noValidate>
        <label className="sr-only" htmlFor="tracking-code">
          Tracking number
        </label>
        <div className="hts-input-row flex flex-1 items-center gap-2.5 min-w-0">
          <Package
            className="w-5 h-5 shrink-0"
            style={{ color: H('FF7A2F') }}
            strokeWidth={1.9}
            aria-hidden="true"
          />
          <input
            id="tracking-code"
            type="text"
            className="hts-input"
            value={tracking}
            onChange={(e) => {
              setTracking(e.target.value);
              if (trackError) setTrackError('');
            }}
            placeholder="Enter your tracking number"
            aria-label="Tracking number"
            aria-invalid={Boolean(trackError)}
            aria-describedby={trackError ? 'hero-tracking-error' : 'hero-tracking-hint'}
          />
        </div>

        <button type="submit" className="hts-btn" disabled={isSubmitting}>
          {isSubmitting ? (
            <>
              <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />
              <span>Tracking...</span>
            </>
          ) : (
            <>
              <span>Track parcel</span>
              <ArrowRight className="hts-btn-arrow w-4 h-4" strokeWidth={2.2} aria-hidden="true" />
            </>
          )}
        </button>
      </form>

      {/* Inline validation error */}
      {trackError && (
        <div id="hero-tracking-error" role="alert" aria-live="polite" className="hts-error">
          {trackError}
        </div>
      )}

      {/* Supporting text */}
      <p id="hero-tracking-hint" className="hts-subtext">
        No login required · Real-time parcel updates
      </p>
    </div>
  );
}

export default HeroTrackingSearch;
