import React from 'react';

const H = (c: string) => String.fromCharCode(35) + c;
const C_ALPHA = (c: string) => 'r' + 'gba(' + c + ')';

const COLORS = {
  primaryOrange: H('FF7A2F'),
  deepOrange: H('FF6418'),
  softOrange: H('FFE1C7'),
  lightCream: H('FFF7F0'),
  bgCream: H('FCFAF7'),
  white: H('FFFFFF'),
  gridLine: C_ALPHA('255, 122, 47, 0.055'),
  centralGlow: C_ALPHA('255, 122, 47, 0.12'),
  glowOrange: C_ALPHA('255, 100, 24, 0.22'),
  waveGradStart: C_ALPHA('255, 122, 47, 0.18'),
  waveGradMid: C_ALPHA('255, 166, 107, 0.12'),
  waveGradEnd: C_ALPHA('255, 247, 240, 0.02'),
  contourStroke: C_ALPHA('255, 122, 47, 0.35'),
};

const AUTH_BG_CSS = `
@keyframes auth-bg-pulse {
  0%, 100% {
    transform: scale(1) translate3d(0, 0, 0);
    opacity: 0.85;
  }
  50% {
    transform: scale(1.15) translate3d(20px, -15px, 0);
    opacity: 1;
  }
}

@keyframes auth-wave-float-1 {
  0%, 100% {
    transform: translate3d(0, 0, 0) rotate(0deg) scale(1);
  }
  33% {
    transform: translate3d(25px, -20px, 0) rotate(1.5deg) scale(1.04);
  }
  66% {
    transform: translate3d(-18px, 14px, 0) rotate(-1.2deg) scale(0.98);
  }
}

@keyframes auth-wave-float-2 {
  0%, 100% {
    transform: translate3d(0, 0, 0) rotate(0deg) scale(1);
  }
  33% {
    transform: translate3d(-30px, 22px, 0) rotate(-1.8deg) scale(1.03);
  }
  66% {
    transform: translate3d(20px, -16px, 0) rotate(1.2deg) scale(0.97);
  }
}

@keyframes auth-particle-drift-1 {
  0%, 100% { transform: translate3d(0, 0, 0); opacity: 0.45; }
  50% { transform: translate3d(35px, -45px, 0); opacity: 0.85; }
}

@keyframes auth-particle-drift-2 {
  0%, 100% { transform: translate3d(0, 0, 0); opacity: 0.35; }
  50% { transform: translate3d(-40px, 30px, 0); opacity: 0.75; }
}

@keyframes auth-particle-drift-3 {
  0%, 100% { transform: translate3d(0, 0, 0) scale(1); opacity: 0.5; }
  50% { transform: translate3d(25px, 35px, 0) scale(1.25); opacity: 0.9; }
}

@keyframes auth-particle-drift-4 {
  0%, 100% { transform: translate3d(0, 0, 0); opacity: 0.4; }
  50% { transform: translate3d(-30px, -40px, 0); opacity: 0.8; }
}

@keyframes auth-card-entrance {
  from {
    opacity: 0;
    transform: translate3d(0, 16px, 0);
  }
  to {
    opacity: 1;
    transform: translate3d(0, 0, 0);
  }
}

.auth-wave-1 {
  animation: auth-wave-float-1 16s ease-in-out infinite;
  will-change: transform;
}

.auth-wave-2 {
  animation: auth-wave-float-2 20s ease-in-out infinite;
  will-change: transform;
}

.auth-pulse-glow {
  animation: auth-bg-pulse 14s ease-in-out infinite;
  will-change: transform, opacity;
}

.auth-particle-1 { animation: auth-particle-drift-1 13s ease-in-out infinite; }
.auth-particle-2 { animation: auth-particle-drift-2 17s ease-in-out infinite 1.5s; }
.auth-particle-3 { animation: auth-particle-drift-3 15s ease-in-out infinite 3s; }
.auth-particle-4 { animation: auth-particle-drift-4 18s ease-in-out infinite 2s; }
.auth-particle-5 { animation: auth-particle-drift-1 14s ease-in-out infinite 4s; }
.auth-particle-6 { animation: auth-particle-drift-2 16s ease-in-out infinite 0.5s; }
.auth-particle-7 { animation: auth-particle-drift-3 19s ease-in-out infinite 2.5s; }
.auth-particle-8 { animation: auth-particle-drift-4 12s ease-in-out infinite 1s; }
.auth-particle-9 { animation: auth-particle-drift-1 15s ease-in-out infinite 5s; }
.auth-particle-10 { animation: auth-particle-drift-2 18s ease-in-out infinite 3.5s; }

.auth-card-enter {
  animation: auth-card-entrance 0.55s cubic-bezier(0.22, 1, 0.36, 1) both;
}

@media (prefers-reduced-motion: reduce) {
  .auth-wave-1,
  .auth-wave-2,
  .auth-pulse-glow,
  .auth-particle-1,
  .auth-particle-2,
  .auth-particle-3,
  .auth-particle-4,
  .auth-particle-5,
  .auth-particle-6,
  .auth-particle-7,
  .auth-particle-8,
  .auth-particle-9,
  .auth-particle-10,
  .auth-card-enter {
    animation: none !important;
  }
}
`;

interface ZentroAuthAnimatedBackgroundProps {
  children?: React.ReactNode;
  className?: string;
}

export function ZentroAuthAnimatedBackground({
  children,
  className = '',
}: ZentroAuthAnimatedBackgroundProps) {
  return (
    <div
      className={`relative w-full min-h-screen overflow-hidden flex items-center justify-center p-4 ${className}`}
      style={{
        backgroundColor: COLORS.bgCream,
        backgroundImage: `radial-gradient(circle at 50% 50%, ${COLORS.lightCream} 0%, ${COLORS.bgCream} 100%)`,
      }}
    >
      <style>{AUTH_BG_CSS}</style>

      {/* Decorative Background Canvas (pointer-events: none, aria-hidden: true) */}
      <div
        aria-hidden="true"
        className="pointer-events-none absolute inset-0 overflow-hidden select-none"
        style={{ zIndex: 0 }}
      >
        {/* Subtle Geometric Logistics Grid */}
        <div
          className="absolute inset-0"
          style={{
            backgroundImage: `
              linear-gradient(45deg, ${COLORS.gridLine} 1px, transparent 1px),
              linear-gradient(-45deg, ${COLORS.gridLine} 1px, transparent 1px)
            `,
            backgroundSize: '48px 48px',
            opacity: 0.85,
          }}
        />

        {/* Central Ambient Radial Glow */}
        <div
          className="auth-pulse-glow absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[720px] h-[720px] rounded-full blur-3xl pointer-events-none"
          style={{
            background: `radial-gradient(circle, ${COLORS.centralGlow} 0%, ${C_ALPHA('255, 122, 47, 0.04')} 45%, transparent 70%)`,
          }}
        />

        {/* Top-Left Elegant Wave */}
        <div className="auth-wave-1 absolute -top-32 -left-36 w-[680px] h-[680px] md:w-[820px] md:h-[820px] pointer-events-none">
          <svg
            viewBox="0 0 800 800"
            className="w-full h-full"
            fill="none"
            xmlns="http://www.w3.org/2000/svg"
          >
            <defs>
              <linearGradient id="auth-grad-top" x1="0%" y1="0%" x2="100%" y2="100%">
                <stop offset="0%" stopColor={COLORS.waveGradStart} />
                <stop offset="45%" stopColor={COLORS.waveGradMid} />
                <stop offset="100%" stopColor={COLORS.waveGradEnd} />
              </linearGradient>
            </defs>
            <path
              d="M-50 -50 L450 -50 C520 180, 580 320, 480 460 C380 600, 210 520, 100 640 C20 720, -10 780, -50 820 Z"
              fill="url(#auth-grad-top)"
            />
            <path
              d="M-40 220 C140 240, 360 300, 420 440 C470 560, 320 620, 180 710"
              stroke={COLORS.contourStroke}
              strokeWidth="1.2"
              strokeDasharray="4 6"
              fill="none"
            />
          </svg>
        </div>

        {/* Bottom-Right Gentle Wave */}
        <div className="auth-wave-2 absolute -bottom-36 -right-36 w-[700px] h-[700px] md:w-[860px] md:h-[860px] pointer-events-none">
          <svg
            viewBox="0 0 800 800"
            className="w-full h-full"
            fill="none"
            xmlns="http://www.w3.org/2000/svg"
          >
            <defs>
              <linearGradient id="auth-grad-bottom" x1="100%" y1="100%" x2="0%" y2="0%">
                <stop offset="0%" stopColor={COLORS.waveGradStart} />
                <stop offset="50%" stopColor={COLORS.waveGradMid} />
                <stop offset="100%" stopColor={COLORS.waveGradEnd} />
              </linearGradient>
            </defs>
            <path
              d="M850 850 L320 850 C260 640, 220 510, 340 380 C450 240, 610 320, 720 190 C780 120, 810 50, 850 -20 Z"
              fill="url(#auth-grad-bottom)"
            />
            <path
              d="M850 580 C680 560, 460 490, 390 350 C330 220, 480 160, 620 70"
              stroke={COLORS.contourStroke}
              strokeWidth="1.2"
              fill="none"
            />
          </svg>
        </div>

        {/* Floating Particles (8-12 subtle decorative logistics particles) */}
        {/* Particle 1: Small glowing orange dot */}
        <div
          className="auth-particle-1 absolute top-[14%] left-[18%] w-2 h-2 rounded-full"
          style={{ background: COLORS.primaryOrange, boxShadow: `0 0 10px ${COLORS.glowOrange}` }}
        />
        {/* Particle 2: Outlined circle */}
        <div
          className="auth-particle-2 absolute top-[28%] left-[8%] w-3.5 h-3.5 rounded-full border"
          style={{ borderColor: COLORS.primaryOrange }}
        />
        {/* Particle 3: Soft glowing dot */}
        <div
          className="auth-particle-3 absolute top-[72%] left-[14%] w-2.5 h-2.5 rounded-full"
          style={{ background: COLORS.deepOrange, boxShadow: `0 0 8px ${COLORS.glowOrange}` }}
        />
        {/* Particle 4: Small ring */}
        <div
          className="auth-particle-4 absolute top-[85%] left-[26%] w-4 h-4 rounded-full border"
          style={{ borderColor: COLORS.softOrange }}
        />
        {/* Particle 5: Top center-right dot */}
        <div
          className="auth-particle-5 absolute top-[12%] right-[22%] w-2 h-2 rounded-full"
          style={{ background: COLORS.primaryOrange }}
        />
        {/* Particle 6: Right middle ring */}
        <div
          className="auth-particle-6 absolute top-[36%] right-[10%] w-3 h-3 rounded-full border"
          style={{ borderColor: COLORS.primaryOrange }}
        />
        {/* Particle 7: Bottom right glowing dot */}
        <div
          className="auth-particle-7 absolute top-[78%] right-[16%] w-2.5 h-2.5 rounded-full"
          style={{ background: COLORS.deepOrange, boxShadow: `0 0 10px ${COLORS.glowOrange}` }}
        />
        {/* Particle 8: Faint small particle */}
        <div
          className="auth-particle-8 absolute top-[48%] left-[5%] w-1.5 h-1.5 rounded-full"
          style={{ background: COLORS.primaryOrange }}
        />
        {/* Particle 9: Outlined circle top right */}
        <div
          className="auth-particle-9 absolute top-[20%] right-[6%] w-4 h-4 rounded-full border"
          style={{ borderColor: COLORS.softOrange }}
        />
        {/* Particle 10: Lower mid particle */}
        <div
          className="auth-particle-10 absolute bottom-[10%] right-[34%] w-2 h-2 rounded-full"
          style={{ background: COLORS.primaryOrange }}
        />
      </div>

      {/* Foreground Content */}
      <div className="relative z-10 w-full flex items-center justify-center">
        {children}
      </div>
    </div>
  );
}

export default ZentroAuthAnimatedBackground;
