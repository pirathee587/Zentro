import React from 'react';
import { Link } from 'react-router-dom';
import './LogoPill.css';

export function LogoBoxSvg() {
  return (
    <svg
      className="sd-logo-box-svg"
      width="28"
      height="24"
      viewBox="0 0 36 28"
      fill="none"
      aria-hidden="true"
    >
      {/* Speed lines */}
      <rect x="1" y="9" width="7" height="2" rx="1" fill="var(--gold, #e5b574)" />
      <rect x="0" y="13.5" width="9" height="2" rx="1" fill="var(--gold, #e5b574)" />
      <rect x="2" y="18" width="6.5" height="2" rx="1" fill="var(--gold, #e5b574)" />
      {/* Isometric box top */}
      <path d="M21 3L32 8.5L21 14L10 8.5L21 3Z" fill="var(--gold-light, #f2cf9c)" />
      {/* Isometric box left face */}
      <path d="M10 8.5L21 14V25.5L10 20V8.5Z" fill="var(--ochre, #c98f4a)" />
      {/* Isometric box right face */}
      <path d="M32 8.5L21 14V25.5L32 20V8.5Z" fill="var(--espresso, #1a1209)" />
      {/* Tape detail */}
      <path d="M25.5 5.75L14.5 11.25L17.5 12.75L28.5 7.25L25.5 5.75Z" fill="var(--warm-pale, #fbe9cf)" opacity="0.7" />
    </svg>
  );
}

export interface LogoPillProps {
  to?: string;
  role?: string;
  ariaLabel?: string;
  className?: string;
}

export const LogoPill: React.FC<LogoPillProps> = ({
  to = '/',
  role,
  ariaLabel,
  className = '',
}) => {
  const label = ariaLabel || (role ? `SwiftDrop ${role} Home` : 'SwiftDrop Home');

  return (
    <Link to={to} className={`sd-logo-pill ${className}`.trim()} aria-label={label}>
      <LogoBoxSvg />
      <span className="sd-brand-name">SwiftDrop</span>
      {role ? <span className="sd-brand-role">{role}</span> : null}
    </Link>
  );
};
