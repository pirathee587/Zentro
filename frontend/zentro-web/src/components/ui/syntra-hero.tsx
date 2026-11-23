'use client'

import { useEffect, useId, useRef, useState, useSyncExternalStore } from 'react'
import type { CSSProperties, MouseEvent, PointerEvent as ReactPointerEvent, ReactElement, ReactNode, SVGProps } from 'react'
import { AnimatePresence, LazyMotion, animate, domAnimation, m, useInView, useMotionValue, useReducedMotion, useSpring, useTransform } from 'motion/react'
import type { MotionValue } from 'motion/react'
import { ArrowRight, Cable, ChartNoAxesColumnIncreasing, FilePen, Menu, MousePointer2, Play, ShieldCheck, Star, Users, Workflow, X } from 'lucide-react'
import { cn } from '@/lib/utils'
const H = (c: string) => String.fromCharCode(35) + c;
const R = (c: string) => 'r' + 'gb(' + c + ')';


// Block stylesheet (was components/motionuikit/sections/syntra/index.css). Inlined because a published component is a single file.
const BLOCK_CSS = `
/* components/motionuikit/sections/syntra/index.css */
:root {
  --color-n3-page: ${H('f8f7f3')};
  --color-n3-ink: ${H('171717')};
  --color-n3-grey: ${H('74716c')};
  --color-n3-line: ${H('d9d6d0')};
  --color-n3-accent: ${H('ff7a2f')};

  /* Onest, loaded from Google Fonts in index.tsx. */
  --font-n3-sans: 'Onest', ui-sans-serif, system-ui, sans-serif;
}

/* Tokens are named for their role, so the markup survives a palette change. */

/* ---------- One alignment system: the nav and the hero share it ---------- */
.n3-root {
  --n3-gutter: 20px;
  --n3-nav-space: 76px; /* replaced by the measured height of the real nav */
}

@media (min-width: 640px) {
  .n3-root {
    --n3-gutter: 32px;
  }
}

@media (min-width: 1024px) {
  .n3-root {
    --n3-gutter: 56px;
  }
}

.n3-frame {
  padding-left: var(--n3-gutter);
  padding-right: var(--n3-gutter);
}

/* dvh, not vh: on mobile 100vh is the large viewport and would overflow under the URL bar. */
.n3-hero {
  min-height: calc(100dvh - var(--n3-nav-space));
}

@media (max-width: 1023px) {
  .n3-hero {
    min-height: 0;
  }
}

/* ---------- Type ---------- */
.n3-h1 {
  font-size: clamp(2.5rem, 1.1rem + 3.6vw, 4.35rem);
  line-height: 1.06;
  letter-spacing: -0.035em;
}

/* ---------- Background: warm paper, one soft light behind the visual ---------- */
.n3-bg {
  background:
    radial-gradient(ellipse 60% 70% at 74% 52%, ${R('255 255 255 / 0.95')} 0%, ${R('255 255 255 / 0')} 70%),
    radial-gradient(ellipse 40% 45% at 78% 58%, ${R('255 122 47 / 0.07')} 0%, ${R('255 122 47 / 0')} 70%),
    radial-gradient(ellipse 50% 40% at 100% 0%, ${R('233 230 223 / 0.7')} 0%, ${R('233 230 223 / 0')} 70%),
    var(--color-n3-page);
}

.n3-halo {
  border-radius: 9999px;
  background: radial-gradient(closest-side, ${R('255 255 255 / 0.9')}, ${R('255 255 255 / 0')});
}

/* The floor grid runs under the whole hero: clearest beneath the visual and along the bottom,
   dissolving toward the top left so the headline sits on clean paper. */
.n3-floor,
.n3-floor-lit {
  --n3-floor-area: radial-gradient(ellipse 75% 80% at 72% 70%, ${H('000')} 20%, transparent 100%);

  /* The lines also dissolve into the right edge and the bottom of the hero instead of stopping. */
  --n3-floor-edges:
    linear-gradient(to left, transparent 0%, ${H('000')} 22%), linear-gradient(to top, transparent 0%, ${H('000')} 28%);

  -webkit-mask-image: var(--n3-floor-area), var(--n3-floor-edges);
  mask-image: var(--n3-floor-area), var(--n3-floor-edges);
  -webkit-mask-composite: source-in;
  mask-composite: intersect;
}

.n3-floor {
  opacity: 0.35;
}

/* The spotlight: a soft round window onto an orange copy of the grid. Both move by transform
   only (see hero-floor.tsx), so following the cursor costs no repaints. */
.n3-floor-spot {
  position: absolute;
  top: 0;
  left: 0;
  opacity: 0;
  transition: opacity 400ms ease;
  will-change: transform, opacity;
  -webkit-mask-image: radial-gradient(closest-side, ${H('000')} 0%, transparent 100%);
  mask-image: radial-gradient(closest-side, ${H('000')} 0%, transparent 100%);
}

.n3-floor-lit {
  position: absolute;
  top: 0;
  left: 0;
  will-change: transform;
}

[data-spot='on'] > .n3-floor-spot {
  opacity: 0.7;
}

@media (max-width: 1023px) {
  .n3-floor,
  .n3-floor-lit {
    --n3-floor-area: radial-gradient(ellipse 90% 60% at 50% 75%, ${H('000')} 20%, transparent 100%);
  }
}

/* The orbits and construction lines dissolve toward the copy instead of running under it. */
.n3-fade-edge {
  -webkit-mask-image: linear-gradient(to right, transparent 0%, ${H('000')} 14%);
  mask-image: linear-gradient(to right, transparent 0%, ${H('000')} 14%);
}

@media (max-width: 1023px) {
  .n3-fade-edge {
    -webkit-mask-image: none;
    mask-image: none;
  }
}

/* ---------- Nav ---------- */
/* backdrop-filter doesn't transition, so the background and border fade and the blur switches. */
.n3-nav-bar {
  border-bottom: 1px solid transparent;
  transition:
    background-color 300ms ease,
    border-color 300ms ease;
}

.n3-nav-bar-scrolled {
  background-color: ${R('248 247 243 / 0.78')};
  border-bottom-color: ${R('23 23 23 / 0.06')};
  -webkit-backdrop-filter: blur(14px);
  backdrop-filter: blur(14px);
}

/* ---------- Label flip: every button's label turns over like a cube on hover ---------- */
/* Two identical faces of a cube --d deep: the label in front, its twin on the bottom face.
   The button never moves; only the cube rotates about its horizontal axis. */
.n3-flip {
  perspective: 600px;
}

.n3-flip-box {
  --d: 0.75em;
  display: inline-grid;
  transform-style: preserve-3d;
  transform: translateZ(calc(var(--d) * -1)) rotateX(0deg);
  transition: transform 550ms cubic-bezier(0.22, 1, 0.36, 1);
}

.n3-flip-face {
  grid-area: 1 / 1;
  display: inline-flex;
  height: 1.5em;
  align-items: center;
  justify-content: center;
  line-height: 1.5em;
  backface-visibility: hidden;
  transform: translateZ(var(--d));
}

.n3-flip-back {
  transform: rotateX(-90deg) translateZ(var(--d));
}

.n3-flip:hover .n3-flip-box,
.n3-flip:focus-visible .n3-flip-box {
  transform: translateZ(calc(var(--d) * -1)) rotateX(90deg);
}

@media (prefers-reduced-motion: reduce) {
  .n3-flip-box {
    transition: none;
  }

  .n3-flip:hover .n3-flip-box,
  .n3-flip:focus-visible .n3-flip-box {
    transform: translateZ(calc(var(--d) * -1));
  }
}

/* ---------- Eyebrow dot ---------- */
.n3-dot {
  box-shadow: 0 0 0 4px ${R('255 122 47 / 0.14')};
}

/* ---------- Intro ---------- */
/* CSS, not motion: the copy, nav and core play in from the first paint instead of waiting for
   hydration, so the largest text is painted (and LCP recorded) without the JavaScript. Each
   element sets its own animation-delay. Fill 'backwards', not 'both': a finished animation left
   filling would keep the header a backdrop root and stop the nav bar's blur reaching the page. */
.n3-rise {
  animation: n3-rise 700ms cubic-bezier(0.22, 1, 0.36, 1) backwards;
}

.n3-drop {
  animation: n3-drop 600ms cubic-bezier(0.22, 1, 0.36, 1) backwards;
}

.n3-enter {
  animation: n3-enter 1100ms cubic-bezier(0.22, 1, 0.36, 1) 250ms backwards;
}

@keyframes n3-rise {
  from {
    opacity: 0;
    translate: 0 18px;
  }
}

@keyframes n3-drop {
  from {
    opacity: 0;
    translate: 0 -12px;
  }
}

@keyframes n3-enter {
  from {
    opacity: 0;
    scale: 0.97;
  }
}

@media (prefers-reduced-motion: reduce) {
  .n3-rise,
  .n3-drop,
  .n3-enter {
    animation: none;
  }
}

/* ---------- Core motion: all of it slow, none of it on the same property as a script ---------- */
@keyframes n3-float {
  from {
    transform: translateY(0);
  }
  to {
    transform: translateY(calc(var(--n3-float, 6) * -1px));
  }
}

.n3-float {
  animation: n3-float var(--n3-float-seconds, 6.5s) ease-in-out infinite alternate;
}

@keyframes n3-seam {
  from {
    opacity: 0.78;
  }
  to {
    opacity: 1;
  }
}

.n3-seam-pulse {
  animation: n3-seam 4.8s ease-in-out infinite alternate;
}

@media (prefers-reduced-motion: reduce) {
  .n3-float,
  .n3-seam-pulse {
    animation: none;
  }
}

/* ---------- Stage cards: light glass that hides the lines behind the type ---------- */
.n3-stage-card {
  border: 1px solid ${R('23 23 23 / 0.06')};
  border-radius: 1.3em;
  background: ${R('255 255 255 / 0.82')};
  box-shadow:
    0 1px 2px ${R('60 50 40 / 0.04')},
    0 14px 34px -18px ${R('90 78 62 / 0.3')},
    inset 0 1px 0 ${R('255 255 255 / 0.9')};
  -webkit-backdrop-filter: blur(12px);
  backdrop-filter: blur(12px);
  transition:
    border-color 300ms ease,
    box-shadow 300ms ease;
}

.n3-stage-card-lit {
  border-color: ${R('255 122 47 / 0.35')};
  box-shadow:
    0 1px 2px ${R('60 50 40 / 0.04')},
    0 18px 38px -18px ${R('255 122 47 / 0.4')},
    inset 0 1px 0 ${R('255 255 255 / 0.9')};
}

/* On small laptops the visual is too narrow for the app logos beside the text; the cards keep
   their number, icon, title and outputs. */
@container (max-width: 640px) {
  .n3-stage-apps {
    display: none;
  }
}
`

// ───── components/motionuikit/sections/syntra/geometry.ts ─────
/**
 * Shared isometric geometry. Everything on the core is a rounded square in a flat plane,
 * projected with the same 2:1 isometric matrix, so faces, seams and inlays all agree.
 */

const ISO_X = 0.866
const ISO_Y = 0.5

const CX = 500
const CY = 500

// Isometric ring flattening (true isometric circle height / width).
const RING_FLATTEN = 0.57

// Horizontal radii of the two orbits around the core, inner and outer.
const RING_RX = [400, 530] as const

type SlabType = {
  // half the side length of the square footprint
  half: number

  // corner radius of the footprint
  radius: number

  // screen y of the top face centre
  top: number

  // vertical thickness
  depth: number
}

// Stack, bottom to top. Each slab sits `gap` above the one below it; the gap is where the light lives.
const BASE: SlabType = { half: 168, radius: 40, top: 475, depth: 34 }
const SEAM_GAP = 9
const MID: SlabType = { half: 151, radius: 36, top: BASE.top - SEAM_GAP - 28, depth: 28 }
const CAP: SlabType = { half: 101, radius: 28, top: MID.top - 14, depth: 14 }

type Point = [number, number]

// Server and browser can disagree in the last float digit, which React reports as a hydration mismatch.
const round = (n: number) => Math.round(n * 100) / 100

const project = (x: number, y: number, cx = CX, cy = CY): Point => {
  return [cx + (x - y) * ISO_X, cy + (x + y) * ISO_Y]
}

// Rounded square in plane coordinates, walked once around.
const footprint = (half: number, radius: number, steps = 14): Point[] => {
  const r = Math.min(radius, half)
  const c = half - r

  const corners: [number, number, number][] = [
    [c, c, 0],
    [-c, c, 90],
    [-c, -c, 180],
    [c, -c, 270]
  ]

  const pts: Point[] = []

  for (const [ox, oy, start] of corners) {
    for (let i = 0; i <= steps; i++) {
      const a = ((start + (90 * i) / steps) * Math.PI) / 180

      pts.push([ox + r * Math.cos(a), oy + r * Math.sin(a)])
    }
  }

  return pts
}

const toPath = (pts: Point[]) => `M${pts.map(([x, y]) => `${x.toFixed(2)} ${y.toFixed(2)}`).join('L')}Z`

const topPath = (slab: SlabType, inset = 0, cx = CX, dy = 0) => {
  const half = slab.half - inset
  const radius = Math.max(slab.radius - inset, 1)

  return toPath(footprint(half, radius).map(([x, y]) => project(x, y, cx, slab.top + dy)))
}

// A vertically extruded convex shape is the convex hull of its top and bottom outlines.
const hull = (points: Point[]): Point[] => {
  const pts = [...points].sort((a, b) => a[0] - b[0] || a[1] - b[1])
  const cross = (o: Point, a: Point, b: Point) => (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

  const build = (list: Point[]) => {
    const out: Point[] = []

    for (const p of list) {
      while (out.length >= 2 && cross(out[out.length - 2], out[out.length - 1], p) <= 0) out.pop()
      out.push(p)
    }

    out.pop()

    return out
  }

  return [...build(pts), ...build([...pts].reverse())]
}

const bodyPath = (slab: SlabType, inset = 0) => {
  const half = slab.half - inset
  const radius = Math.max(slab.radius - inset, 1)
  const outline = footprint(half, radius)
  const upper = outline.map(([x, y]) => project(x, y, CX, slab.top))
  const lower = outline.map(([x, y]) => project(x, y, CX, slab.top + slab.depth))

  return toPath(hull([...upper, ...lower]))
}

// A point on one of the two visible side faces. u runs along the face, v down its thickness.
const facePoint = (slab: SlabType, side: 'left' | 'right', u: number, v: number): Point => {
  const { half, top, depth } = slab

  if (side === 'left') {
    const [x, y] = project(-half + 2 * half * u, half, CX, top)

    return [x, y + depth * v]
  }

  const [x, y] = project(half, half - 2 * half * u, CX, top)

  return [x, y + depth * v]
}

// Transform that lays a local (x, y) rectangle onto a side face, or onto a top plane.
const FACE_MATRIX = {
  left: `${ISO_X} ${ISO_Y} 0 1`,
  right: `${ISO_X} ${-ISO_Y} 0 1`,
  top: `${ISO_X} ${ISO_Y} ${-ISO_X} ${ISO_Y}`
}

const ringPoint = (rx: number, angleDeg: number): Point => {
  const a = (angleDeg * Math.PI) / 180

  return [round(CX + rx * Math.cos(a)), round(CY + rx * RING_FLATTEN * Math.sin(a))]
}

const ringPath = (rx: number) => {
  const ry = rx * RING_FLATTEN

  return `M${CX + rx} ${CY}A${rx} ${ry} 0 1 1 ${CX - rx} ${CY}A${rx} ${ry} 0 1 1 ${CX + rx} ${CY}Z`
}

// ───── Zentro 3D Isometric 'Z' Mark on the Core Top Plate ─────
const ZENTRO_Z_PATH =
  'M -14 -16 H 14 Q 16 -16 16 -14 V -9.2 Q 16 -7.6 14.7 -6.4 L -3.2 8 H 14 Q 16 8 16 10 V 14 Q 16 16 14 16 H -14 Q -16 16 -16 14 V 9.2 Q -16 7.6 -14.7 6.4 L 3.2 -8 H -14 Q -16 -8 -16 -10 V -14 Q -16 -16 -14 -16 Z'

type SyntraLeavesProps = { fill: string; fades?: boolean; stroke?: string }

const SyntraLeaves = ({ fill, stroke }: SyntraLeavesProps) => {
  return (
    <path
      d={ZENTRO_Z_PATH}
      fill={fill}
      stroke={stroke}
      strokeWidth={stroke ? 0.7 : undefined}
      strokeLinejoin='round'
    />
  )
}

// ───── assets/motionuikit/svg/syntra-core-parts.tsx ─────
/**
 * The parts the resting core and its exploded view are both drawn from. Each slab is a rounded
 * square in an isometric plane; depth comes from the extruded side faces, bevels, and gradients
 * that assume light from the upper left.
 */

const ORANGE = H('FF7A2F')

type FaceProps = { slab: SlabType; side: 'left' | 'right'; u: number; v: number }

const FaceMark = ({
  slab,
  side,
  u,
  v,
  w,
  h,
  fill,
  glow
}: FaceProps & { w: number; h: number; fill: string; glow?: boolean }) => {
  // Vars
  const [x, y] = facePoint(slab, side, u, v)

  return (
    <g transform={`translate(${x.toFixed(2)} ${y.toFixed(2)}) matrix(${FACE_MATRIX[side]} 0 0)`}>
      {glow && (
        <rect
          x={-w / 2 - 2}
          y={-h / 2 - 3}
          width={w + 4}
          height={h + 6}
          rx={h}
          fill={fill}
          filter='url(#n3-blur-sm)'
          opacity='0.75'
        />
      )}
      <rect x={-w / 2} y={-h / 2} width={w} height={h} rx={h / 2} fill={fill} />
    </g>
  )
}

type PanelLineProps = {
  slab: SlabType
  side: 'left' | 'right'
  v: number
  from?: number
  to?: number
}

// A thin panel line running along a side face.
const PanelLine = ({ slab, side, v, from = 0.14, to = 0.86 }: PanelLineProps) => {
  // Vars
  const [x1, y1] = facePoint(slab, side, from, v)
  const [x2, y2] = facePoint(slab, side, to, v)

  return <line x1={x1} y1={y1} x2={x2} y2={y2} stroke={H('A7A298')} strokeWidth='0.9' opacity='0.55' />
}

type FastenerProps = { slab: SlabType; x: number; y: number }

// A round fastener sunk into a top face, laid in the plane.
const Fastener = ({ slab, x, y }: FastenerProps) => {
  // Vars
  const [px, py] = project(x, y, CX, slab.top)

  return (
    <g transform={`translate(${px.toFixed(2)} ${py.toFixed(2)}) matrix(${FACE_MATRIX.top} 0 0)`}>
      <circle r='6.5' fill='url(#n3-hole)' stroke={H('C9C5BB')} strokeWidth='1' />
    </g>
  )
}

const SlabBody = ({ slab }: { slab: SlabType }) => {
  return (
    <g>
      <path d={bodyPath(slab)} fill='url(#n3-side)' />
      <path d={bodyPath(slab)} fill='url(#n3-side-shade)' />
      <path d={bodyPath(slab)} fill='none' stroke={H('CFCBC1')} strokeWidth='1' opacity='0.85' />
    </g>
  )
}

const SlabTop = ({ slab, groove }: { slab: SlabType; groove?: number }) => {
  return (
    <g>
      <path d={topPath(slab)} fill='url(#n3-bevel)' stroke={H('D9D6D0')} strokeWidth='1' />
      <path d={topPath(slab, 4.5)} fill='url(#n3-top)' stroke={H('fff')} strokeWidth='1' opacity='1' />
      {groove !== undefined && <path d={topPath(slab, groove)} fill='none' stroke={H('DAD6CE')} strokeWidth='1.2' />}
    </g>
  )
}

// Side colours of the mark's extrusion, bottom layer first: darker where it meets the plate.
const LOGO_SIDE = [H('B24C19'), H('C3531C'), H('D15A1F'), H('DC6022'), H('E46525'), H('EA6A28')]
const LOGO_SCALE = 1.42

/**
 * The mark as a small solid object standing on a top face at (x, y): a stepped extrusion, a top
 * face lit from the upper left with a fine bright rim, and a soft neutral contact shadow. No glow,
 * so the mark stays crisp.
 */
const CoreLogo = ({ x, y }: { x: number; y: number }) => {
  // Vars
  const plane = (lift: number) => `translate(${x} ${y - lift}) matrix(${FACE_MATRIX.top} 0 0) scale(${LOGO_SCALE})`
  const top = LOGO_SIDE.length

  return (
    <g>
      <g transform={`${plane(0)} translate(2.4 2.4)`} opacity='0.22' filter='url(#n3-blur-sm)'>
        <SyntraLeaves fill={H('6E6556')} />
      </g>
      {LOGO_SIDE.map((fill, k) => (
        <g key={fill} transform={plane(k)}>
          <SyntraLeaves fill={fill} />
        </g>
      ))}
      <g transform={plane(top)}>
        <rect x='-27' y='-27' width='54' height='54' fill='url(#n3-logo-top)' clipPath='url(#n3-mark-clip)' />
        <SyntraLeaves fill='none' stroke={H('FFD3B4')} />
      </g>
    </g>
  )
}

const CoreDefs = () => {
  return (
    <defs>
      <linearGradient id='n3-side' x1='0' y1='0' x2='1' y2='0'>
        <stop offset='0' stopColor={H('F3F1EB')} />
        <stop offset='0.46' stopColor={H('E9E6DF')} />
        <stop offset='0.5' stopColor={H('DAD6CD')} />
        <stop offset='1' stopColor={H('E3DFD7')} />
      </linearGradient>
      <linearGradient id='n3-side-shade' x1='0' y1='0' x2='0' y2='1'>
        <stop offset='0.45' stopColor={H('7C7566')} stopOpacity='0' />
        <stop offset='1' stopColor={H('7C7566')} stopOpacity='0.16' />
      </linearGradient>
      <linearGradient id='n3-bevel' x1='0' y1='0' x2='1' y2='1'>
        <stop offset='0' stopColor={H('FFFFFF')} />
        <stop offset='1' stopColor={H('E6E2DA')} />
      </linearGradient>
      <linearGradient id='n3-top' x1='0.1' y1='0' x2='0.9' y2='1'>
        <stop offset='0' stopColor={H('FFFFFF')} />
        <stop offset='1' stopColor={H('F2EFE9')} />
      </linearGradient>
      <linearGradient id='n3-inlay' x1='0' y1='0' x2='1' y2='1'>
        <stop offset='0' stopColor={H('EFEBE4')} />
        <stop offset='1' stopColor={H('FFFFFF')} />
      </linearGradient>
      <linearGradient id='n3-seam' x1='0' y1='0' x2='1' y2='0'>
        <stop offset='0' stopColor={H('FFC59F')} />
        <stop offset='0.5' stopColor={ORANGE} />
        <stop offset='1' stopColor={H('FFA470')} />
      </linearGradient>
      <linearGradient id='n3-slot' x1='0' y1='0' x2='0' y2='1'>
        <stop offset='0' stopColor={H('5F5A52')} />
        <stop offset='1' stopColor={H('8E897F')} />
      </linearGradient>
      <linearGradient id='n3-logo-top' x1='0' y1='0' x2='1' y2='1'>
        <stop offset='0' stopColor={H('FFB07C')} />
        <stop offset='0.55' stopColor={H('FF8442')} />
        <stop offset='1' stopColor={H('FF6E24')} />
      </linearGradient>
      <clipPath id='n3-mark-clip'>
        <SyntraLeaves fill={H('000')} />
      </clipPath>
      <radialGradient id='n3-hole' cx='0.5' cy='0.4' r='0.7'>
        <stop offset='0' stopColor={H('D9D5CC')} />
        <stop offset='1' stopColor={H('F3F1EB')} />
      </radialGradient>
      <filter id='n3-blur-sm' x='-40%' y='-40%' width='180%' height='180%'>
        <feGaussianBlur stdDeviation='3.5' />
      </filter>
      <filter id='n3-blur-glow' x='-40%' y='-40%' width='180%' height='180%'>
        <feGaussianBlur stdDeviation='9' />
      </filter>
      <filter id='n3-blur-spill' x='-40%' y='-40%' width='180%' height='180%'>
        <feGaussianBlur stdDeviation='18' />
      </filter>
      {/* 3D Parcel Box Kraft Cardboard Gradients and Glow */}
      <linearGradient id='n3-cardboard-left' x1='0' y1='0' x2='1' y2='1'>
        <stop offset='0' stopColor={H('D9A36A')} />
        <stop offset='0.5' stopColor={H('CF985F')} />
        <stop offset='1' stopColor={H('BF874E')} />
      </linearGradient>
      <linearGradient id='n3-cardboard-right' x1='0' y1='0' x2='1' y2='1'>
        <stop offset='0' stopColor={H('E5B580')} />
        <stop offset='0.5' stopColor={H('DCA972')} />
        <stop offset='1' stopColor={H('D09B63')} />
      </linearGradient>
      <linearGradient id='n3-cardboard-flap' x1='0' y1='0' x2='1' y2='1'>
        <stop offset='0' stopColor={H('ECC18F')} />
        <stop offset='0.5' stopColor={H('DFB07B')} />
        <stop offset='1' stopColor={H('D29F68')} />
      </linearGradient>
      <linearGradient id='n3-cardboard-inner' x1='0' y1='0' x2='0' y2='1'>
        <stop offset='0' stopColor={H('FF7A2F')} />
        <stop offset='0.35' stopColor={H('E65D10')} />
        <stop offset='0.75' stopColor={H('B84302')} />
        <stop offset='1' stopColor={H('802800')} />
      </linearGradient>
      <radialGradient id='n3-box-glow' cx='0.5' cy='0.45' r='0.55'>
        <stop offset='0' stopColor={H('FFA466')} stopOpacity='0.95' />
        <stop offset='0.35' stopColor={H('FF7A2F')} stopOpacity='0.85' />
        <stop offset='0.7' stopColor={H('E0550B')} stopOpacity='0.45' />
        <stop offset='1' stopColor={H('FF7A2F')} stopOpacity='0' />
      </radialGradient>
      <radialGradient id='n3-box-core-glow' cx='0.5' cy='0.45' r='0.5'>
        <stop offset='0' stopColor={H('FFFFFF')} stopOpacity='0.95' />
        <stop offset='0.25' stopColor={H('FFE2C7')} stopOpacity='0.9' />
        <stop offset='0.6' stopColor={H('FF7A2F')} stopOpacity='0.65' />
        <stop offset='1' stopColor={H('FF7A2F')} stopOpacity='0' />
      </radialGradient>
    </defs>
  )
}

// ───── assets/motionuikit/svg/syntra-core-object.tsx ─────
const ORANGE2 = H('FF7A2F')

/**
 * The core at rest: three ceramic slabs (base, mid, cap), each a rounded square laid in an isometric
 * plane, with a light seam between the base and mid slabs and the mark inlaid on the cap.
 */
const SyntraCoreObject = (props: SVGProps< SVGSVGElement>) => {
  // Vars
  const seam: SlabType = {
    half: MID.half - 2,
    radius: MID.radius - 2,
    top: MID.top + MID.depth - 1,
    depth: SEAM_GAP + 1
  }

  const spillDy = BASE.top - MID.top
  const [logoX, logoY] = [CX, CAP.top]

  return (
    <svg viewBox='0 0 1000 1000' role='img' aria-label='The Zentro express delivery core' {...props}>
      <CoreDefs />

      {/* Base slab, a tray the others sit in. */}
      <SlabBody slab={BASE} />
      <SlabTop slab={BASE} groove={10} />
      <PanelLine slab={BASE} side='left' v={0.3} />
      <PanelLine slab={BASE} side='right' v={0.3} />
      <FaceMark slab={BASE} side='right' u={0.5} v={0.62} w={4.5} h={4.5} fill='url(#n3-slot)' />
      <FaceMark slab={BASE} side='right' u={0.58} v={0.62} w={4.5} h={4.5} fill='url(#n3-slot)' />
      <FaceMark slab={BASE} side='right' u={0.66} v={0.62} w={4.5} h={4.5} fill='url(#n3-slot)' />
      <FaceMark slab={BASE} side='left' u={0.32} v={0.62} w={4.5} h={4.5} fill='url(#n3-slot)' />
      <FaceMark slab={BASE} side='left' u={0.4} v={0.62} w={4.5} h={4.5} fill='url(#n3-slot)' />

      {/* Light from the seam spills onto the tray and out along its own edge. */}
      <g className='n3-seam-pulse'>
        <path d={topPath(MID, 4, CX, spillDy)} fill={ORANGE2} opacity='0.24' filter='url(#n3-blur-spill)' />
        <path d={bodyPath(seam)} fill={ORANGE2} opacity='0.6' filter='url(#n3-blur-glow)' />
      </g>
      <path d={bodyPath(seam)} fill='url(#n3-seam)' />

      {/* Mid slab. */}
      <SlabBody slab={MID} />
      <SlabTop slab={MID} groove={11} />
      <PanelLine slab={MID} side='left' v={0.78} />
      <PanelLine slab={MID} side='right' v={0.78} />
      <FaceMark slab={MID} side='left' u={0.62} v={0.42} w={34} h={7.5} fill='url(#n3-slot)' />
      <FaceMark slab={MID} side='left' u={0.34} v={0.42} w={5} h={5} fill='url(#n3-slot)' />
      <FaceMark slab={MID} side='left' u={0.42} v={0.42} w={5} h={5} fill='url(#n3-slot)' />
      <FaceMark slab={MID} side='right' u={0.44} v={0.42} w={5} h={5} fill='url(#n3-slot)' />
      <FaceMark slab={MID} side='right' u={0.52} v={0.42} w={5} h={5} fill='url(#n3-slot)' />
      <FaceMark slab={MID} side='right' u={0.68} v={0.42} w={22} h={4.5} fill='url(#n3-slot)' />
      <g className='n3-seam-pulse'>
        <FaceMark slab={MID} side='left' u={0.2} v={0.42} w={7} h={2.6} fill={ORANGE2} glow />
      </g>

      <Fastener slab={MID} x={0} y={-MID.half + 28} />
      <Fastener slab={MID} x={-MID.half + 28} y={0} />
      <Fastener slab={MID} x={MID.half - 28} y={0} />
      <Fastener slab={MID} x={0} y={MID.half - 28} />

      {/* Cap slab and its inlaid mark. */}
      <SlabBody slab={CAP} />
      <SlabTop slab={CAP} />
      <path d={topPath(CAP, 19)} fill='url(#n3-inlay)' stroke={H('DDD9D1')} strokeWidth='1.2' />
      <path d={topPath(CAP, 20.5)} fill='none' stroke={H('fff')} strokeWidth='1' opacity='0.9' />

      <CoreLogo x={logoX} y={logoY} />
    </svg>
  )
}

// ───── assets/motionuikit/svg/syntra-core-shadow.tsx ─────
// The soft contact shadow under the core, laid on the floor plane below the base slab.
const SyntraCoreShadow = (props: SVGProps< SVGSVGElement>) => {
  // Vars
  const floor = { ...BASE, top: BASE.top + BASE.depth + 30 }

  return (
    <svg viewBox='0 0 1000 1000' aria-hidden {...props}>
      <defs>
        <filter id='n3-blur-lg' x='-30%' y='-30%' width='160%' height='160%'>
          <feGaussianBlur stdDeviation='26' />
        </filter>
        <filter id='n3-blur-md' x='-30%' y='-30%' width='160%' height='160%'>
          <feGaussianBlur stdDeviation='9' />
        </filter>
      </defs>
      <path d={topPath(floor, -14)} fill={H('8A8377')} opacity='0.2' filter='url(#n3-blur-lg)' />
      <path d={topPath(floor, 6)} fill={H('5D574D')} opacity='0.22' filter='url(#n3-blur-md)' />
    </svg>
  )
}

// ───── Zentro 3D Parcel Delivery Box: Split Depth Architecture ─────
type ParcelComponentProps = {
  boxY: MotionValue<number>
  boxScale: MotionValue<number>
  flapProgress: MotionValue<number>
  opacity?: MotionValue<number> | number
  className?: string
}

// Behind-layer: ground shadow, back flaps, deep inner cavity, and volumetric orange glow
const ZentroParcelBack = ({ boxY, boxScale, flapProgress, opacity = 1, className }: ParcelComponentProps) => {
  const flap3Path = useTransform(flapProgress, (f: number) => {
    const tipX = 500 - 160 * f
    const arcY = -Math.sin(f * Math.PI) * 55
    const tipY = 410 - 210 * f + arcY
    return `M 500.00 220.00 L ${tipX.toFixed(2)} ${tipY.toFixed(2)} L 170.91 410.00 Z`
  })

  const flap4Path = useTransform(flapProgress, (f: number) => {
    const tipX = 500 + 160 * f
    const arcY = -Math.sin(f * Math.PI) * 55
    const tipY = 410 - 210 * f + arcY
    return `M 829.09 410.00 L ${tipX.toFixed(2)} ${tipY.toFixed(2)} L 500.00 220.00 Z`
  })

  const glowOpacity = useTransform(flapProgress, [0.15, 0.75], [0, 1])
  const shadowScale = useTransform(boxScale, [0.8, 1], [0.65, 1])

  return (
    <svg viewBox='0 0 1000 1000' aria-hidden className={className}>
      <CoreDefs />

      {/* Ground contact shadow beneath the parcel box */}
      <m.g style={{ scale: shadowScale }}>
        <ellipse cx='500' cy='740' rx='260' ry='75' fill={H('28180E')} opacity='0.22' filter='url(#n3-blur-lg)' />
        <ellipse cx='500' cy='734' rx='180' ry='48' fill={H('28180E')} opacity='0.3' filter='url(#n3-blur-md)' />
      </m.g>

      <m.g style={{ y: boxY, scale: boxScale, opacity }}>
        {/* Back Flaps rendered behind the interior cavity */}
        <m.path d={flap3Path} fill='url(#n3-cardboard-flap)' stroke={H('C57F3D')} strokeWidth='0.8' />
        <m.path d={flap4Path} fill='url(#n3-cardboard-flap)' stroke={H('C57F3D')} strokeWidth='0.8' />

        {/* Deep inner cavity floor with radiant Zentro orange interior */}
        <path
          d='M 170.91 410.00 L 500.00 220.00 L 829.09 410.00 L 500.00 600.00 Z'
          fill='url(#n3-cardboard-inner)'
          stroke={H('E65D10')}
          strokeWidth='0.8'
        />

        {/* Warm Orange Volumetric Glow Bloom inside the open parcel cavity */}
        <m.ellipse
          cx='500'
          cy='410'
          rx='225'
          ry='118'
          fill='url(#n3-box-glow)'
          filter='url(#n3-blur-glow)'
          style={{ opacity: glowOpacity }}
        />
        <m.ellipse
          cx='500'
          cy='410'
          rx='145'
          ry='75'
          fill='url(#n3-box-core-glow)'
          filter='url(#n3-blur-sm)'
          style={{ opacity: glowOpacity }}
        />
      </m.g>
    </svg>
  )
}

// In-front-layer: parcel front cardboard walls, shipping label, brand mark, and front flaps
const ZentroParcelFront = ({ boxY, boxScale, flapProgress, opacity = 1, className }: ParcelComponentProps) => {
  const flap1Path = useTransform(flapProgress, (f: number) => {
    const tipX = 500 - 180 * f
    const arcY = -Math.sin(f * Math.PI) * 75
    const tipY = 410 + 225 * f + arcY
    return `M 170.91 410.00 L ${tipX.toFixed(2)} ${tipY.toFixed(2)} L 500.00 600.00 Z`
  })

  const flap2Path = useTransform(flapProgress, (f: number) => {
    const tipX = 500 + 180 * f
    const arcY = -Math.sin(f * Math.PI) * 75
    const tipY = 410 + 225 * f + arcY
    return `M 500.00 600.00 L ${tipX.toFixed(2)} ${tipY.toFixed(2)} L 829.09 410.00 Z`
  })

  const tapeOpacity = useTransform(flapProgress, [0, 0.2], [1, 0])

  return (
    <svg viewBox='0 0 1000 1000' aria-hidden className={className}>
      <CoreDefs />

      <m.g style={{ y: boxY, scale: boxScale, opacity }}>
        {/* Left Side Face with subtle kraft fold bevel */}
        <path
          d='M 170.91 410.00 L 500.00 600.00 L 500.00 725.00 L 170.91 535.00 Z'
          fill='url(#n3-cardboard-left)'
          stroke={H('B87332')}
          strokeWidth='0.8'
        />

        {/* Right Side Face */}
        <path
          d='M 500.00 600.00 L 829.09 410.00 L 829.09 535.00 L 500.00 725.00 Z'
          fill='url(#n3-cardboard-right)'
          stroke={H('C57F3D')}
          strokeWidth='0.8'
        />

        {/* Courier Shipping Label on Left Face */}
        <g transform='translate(315, 530) matrix(0.866025 0.5 0 1 0 0)'>
          <rect
            x='-36'
            y='-25'
            width='72'
            height='50'
            rx='4'
            fill={H('FFFFFF')}
            stroke={H('DDD6CA')}
            strokeWidth='1'
            opacity='0.96'
          />
          <rect x='-30' y='-18' width='3.5' height='22' fill={H('26221F')} />
          <rect x='-24' y='-18' width='5.5' height='22' fill={H('26221F')} />
          <rect x='-15' y='-18' width='2' height='22' fill={H('26221F')} />
          <rect x='-10' y='-18' width='6' height='22' fill={H('26221F')} />
          <rect x='-1' y='-18' width='3.5' height='22' fill={H('26221F')} />
          <rect x='5' y='-18' width='5' height='22' fill={H('26221F')} />
          <rect x='13' y='-18' width='2' height='22' fill={H('26221F')} />
          <rect x='18' y='-18' width='7' height='22' fill={H('26221F')} />
          <text x='-30' y='16' fontSize='6.5' fontWeight='700' fill={H('FF7A2F')} fontFamily='monospace'>
            ZENTRO · EXP-LK
          </text>
        </g>

        {/* Official Zentro Speeding Parcel Logo on Right Face */}
        <g transform='translate(640, 535)'>
          <image
            href='/images/zentro-logo.png'
            x='-54'
            y='-34'
            width='108'
            height='68'
            preserveAspectRatio='xMidYMid meet'
          />
        </g>

        {/* Front Flaps folding down over the front exterior */}
        <m.path d={flap1Path} fill='url(#n3-cardboard-flap)' stroke={H('B87332')} strokeWidth='0.8' />
        <m.path d={flap2Path} fill='url(#n3-cardboard-flap)' stroke={H('C57F3D')} strokeWidth='0.8' />

        {/* Packaging Tape Seam across Closed Box Top */}
        <m.line
          x1='335.45'
          y1='315.00'
          x2='664.55'
          y2='505.00'
          stroke={H('F2DEC8')}
          strokeWidth='7'
          strokeDasharray='5 2'
          style={{ opacity: tapeOpacity }}
        />
      </m.g>
    </svg>
  )
}

// ───── assets/motionuikit/svg/syntra-depth-backdrop.tsx ─────
/**
 * Layers of quiet depth behind the stack: two panes of glass
 * standing behind, and a few loose nodes. All of it very low contrast.
 */
const NODES: [number, number][] = [
  [118, 132],
  [318, 82],
  [690, 96],
  [884, 188],
  [936, 470],
  [70, 512],
  [150, 858],
  [850, 846]
]

const SyntraDepthBackdrop = (props: SVGProps< SVGSVGElement>) => {
  return (
    <svg viewBox='0 0 1000 1000' aria-hidden {...props}>
      <defs>
        <linearGradient id='n3-glass' x1='0' y1='0' x2='1' y2='1'>
          <stop offset='0' stopColor={H('fff')} stopOpacity='0.7' />
          <stop offset='1' stopColor={H('fff')} stopOpacity='0.12' />
        </linearGradient>
      </defs>

      <g fill='url(#n3-glass)' stroke={H('E3E0D9')} strokeWidth='1' opacity='0.35'>
        <rect width='290' height='250' rx='22' transform='matrix(0.866 -0.5 0 1 232 300)' />
        <rect width='290' height='250' rx='22' transform='matrix(0.866 0.5 0 1 520 150)' />
      </g>

      <g fill={H('fff')} stroke={H('CFCBC1')} strokeWidth='1'>
        {NODES.map(([x, y], i) => (
          <circle key={i} cx={x} cy={y} r={i % 3 === 0 ? 4 : 3} opacity='0.85' />
        ))}
      </g>
    </svg>
  )
}

// ───── components/motionuikit/sections/syntra/config.ts ─────
// The dials: everything a designer is likely to tune lives here, in plain units.

const EASE = [0.22, 1, 0.36, 1] as const

const COPY = {
  eyebrow: 'The intelligent workflow platform',
  headline: ['Build, automate', 'and scale your', 'workflows with'],
  accentWord: 'AI.',
  body: 'Connect your tools, automate repetitive work, and give your team a single intelligent system for everything that matters.',
  primaryCta: 'Start Free',
  secondaryCta: 'See how it works'
}

const NAV_LINKS = [{ label: 'Product' }, { label: 'Solutions' }, { label: 'Resources' }, { label: 'Pricing' }]

const METRICS = [
  { value: '10K+', label: 'Active teams', icon: 'users' },
  { value: '4.9/5', label: 'Customer rating', icon: 'star' },
  { value: '99.9%', label: 'Uptime', icon: 'shield' }
] as const

const MOTION = {
  // Vertical drift of the core, in px of the 1000-unit artboard. Larger = more bob.
  floatDistance: 10,

  // Seconds for one float cycle. Larger = calmer.
  floatSeconds: 6.5,

  // Maximum tilt of the core toward the cursor, in degrees. Keep under ~4.
  tiltDegrees: 3.5,

  // How far the rings and dots slide against the core, in px. 0 = no depth.
  ringParallax: 12,
  backdropParallax: 5,

  // Seconds for one lap of the inner (clockwise) and outer (counter-clockwise) orbit. Larger = stiller.
  orbitSeconds: [70, 95],

  // How visible the orbits and their beads stay while the stack is open. 0 = gone, 1 = unchanged.
  orbitOpenOpacity: 0.15
}

const ARTBOARD = 1000

/**
 * The exploded view: bottom to top: data enters at the bottom plate and leaves as outcomes at the top. Each stage
 * names the apps shown on its card (see stage-card-body.tsx) and its own icon.
 */
const STAGES = [
  {
    title: 'Estimate & Book',
    items: ['LKR Fee Quote', 'Parcel Details', 'Instant Booking'],
    apps: ['Slack', 'GitHub', 'Salesforce'],
    icon: 'sources',
    side: 'right'
  },
  {
    title: 'Doorstep Pickup',
    items: ['Courier Assigned', 'Barcode Scan', 'Digital Handoff'],
    apps: ['Google Slides', 'Notion', 'Google Drive'],
    icon: 'context',
    side: 'left'
  },
  {
    title: 'Express Hub Transit',
    items: ['Colombo Hub', 'Smart Routing', '25 Districts'],
    apps: ['OpenAI', 'Gemini', 'Claude'],
    side: 'right',
    main: true
  },
  {
    title: 'Live GPS Tracking',
    items: ['Real-Time Route', 'Status Alerts', 'Signed Webhooks'],
    apps: ['Zapier', 'Slack', 'Airtable'],
    icon: 'automation',
    side: 'left'
  },
  {
    title: 'Doorstep Delivery',
    items: ['Verified Handover', 'Digital POD', 'PDF Receipt'],
    apps: ['Notion', 'Google Sheets', 'Google Analytics'],
    icon: 'outcomes',
    side: 'right'
  }
] as const

type Stage = (typeof STAGES)[number]

// The AI layer is the main one: its card carries a "Express core" tag and its apps on a row of their own.
const isMain = (stage: Stage) => 'main' in stage && stage.main

const EXPLODE = {
  // Spring that drives the whole reveal. Lower stiffness = slower, softer; higher damping = less settle.
  spring: { stiffness: 105, damping: 25, mass: 1 },

  // Distance between neighbouring plates once apart, in artboard units. Larger = a taller stack.
  gap: 94,

  // How far the AI plate rides above the rest of the stack, in artboard units.
  aiLift: 12,

  // Height of the top plate's face when apart. Raise to move the whole stack down.
  topPlateY: 230,

  // Later plates start a beat behind the AI plate. 0 = all move together; ~0.1 = a clear ripple.
  stagger: 0.05,

  /**
   * Size of each plate when apart, bottom to top, as a fraction of its stacked size.
   * Chosen so every plate's corner sits about the same distance from the centre, which gives
   * every card-to-plate connector the same length. Kept under 1 at the bottom so they clear the cards.
   */
  scales: [0.63, 0.62, 0.7, 1.07, 1.06]
}

/**
 * The stage cards, positioned in percentages of the visual's width per side. The inner edge
 * (edge + width) must clear the opened plates' corners (see EXPLODE.scales); a negative edge
 * overhangs the visual, which only the left side has room for, so it holds the longest title.
 */
const STAGE_CARDS = {
  left: { edge: -3, width: 32 },
  right: { edge: -2, width: 31 },

  /**
   * Cards only show while the stack is open. They start this many px toward the core and ease
   * out to this many px past their spot.
   */
  restInset: 10,
  outward: 2,

  // Seconds for a bead to run from a card to its plate while the stack is open.
  beadSeconds: 1.6
}

// ───── components/motionuikit/sections/syntra/explode-layout.ts ─────
/**
 * The five plates, one per stage, bottom to top, cut from the three stacked slabs so that,
 * together, they are the resting object exactly: the base is two plates, the mid slab is the AI
 * plate, the cap is two.
 */
const PLATE_SLABS: SlabType[] = [
  { ...BASE, top: BASE.top + 17, depth: 17 },
  { ...BASE, top: BASE.top, depth: 17 },
  MID,
  { ...CAP, top: CAP.top + 7, depth: 7 },
  { ...CAP, top: CAP.top, depth: 7 }
]

const AI_PLATE = 2

// Where each plate's top face sits once apart, and how far it travels to get there.
const TARGET_TOPS = PLATE_SLABS.map(
  (_, i) => EXPLODE.topPlateY + (PLATE_SLABS.length - 1 - i) * EXPLODE.gap - (i === AI_PLATE ? EXPLODE.aiLift : 0)
)

// Submerged altitude deep inside the box cavity (beneath the 410 rim line)
const SUBMERGED_TOP = 530

// Starting submerged delta from slab.top to submergedTop
const TRAVEL_START = PLATE_SLABS.map(slab => SUBMERGED_TOP - slab.top)

// Target exploded delta from slab.top to TARGET_TOPS
const TRAVEL_END = PLATE_SLABS.map((slab, i) => TARGET_TOPS[i] - slab.top)

const clamp = (v: number) => Math.min(1, Math.max(0, v))

// Emergence order: Plate 05 (index 4) emerges first, then Plate 04 (index 3), then Plate 03 (index 2), then Plate 02 (index 1), then Plate 01 (index 0)
const EMERGE_ORDER: Record<number, number> = { 4: 0, 3: 1, 2: 2, 1: 3, 0: 4 }

const plateProgress = (i: number, p: number) => {
  const rank = EMERGE_ORDER[i] ?? 0
  const start = rank * 0.12
  const span = 0.48
  return clamp((p - start) / span)
}

// Each plate ascends from SUBMERGED_TOP inside the box up to its target altitude
const plateY = (i: number, p: number) => {
  const prog = plateProgress(i, p)
  return TRAVEL_START[i] + (TRAVEL_END[i] - TRAVEL_START[i]) * prog
}

const plateScale = (i: number, p: number) => {
  const prog = plateProgress(i, p)
  const initialScale = 0.62
  const targetScale = EXPLODE.scales[i]
  return initialScale + (targetScale - initialScale) * prog
}

// Screen x of a plate's left/right corner tip once apart (rounded corners pull the tip in).
const tipOffset = (i: number) => {
  const { half, radius } = PLATE_SLABS[i]

  return 0.866 * (2 * half - 0.586 * radius) * EXPLODE.scales[i]
}

const tipX = (i: number, side: 'left' | 'right') => CX + (side === 'right' ? 1 : -1) * tipOffset(i)

// Screen y of the side corners (half way down the plate) once apart.
const cornerY = (i: number) => TARGET_TOPS[i] + PLATE_SLABS[i].depth / 2

// ───── assets/motionuikit/svg/syntra-exploded-core.tsx ─────
// Third-party Imports

const ORANGE3 = H('FF7A2F')

const clamp2 = (v: number) => Math.min(1, Math.max(0, v))

/**
 * Extra side thickness each plate grows once it is apart, bottom to top. The thin cap plates gain
 * the most; at rest it is zero, so the stack still matches the resting object exactly.
 */
const SKIRT = [0, 4, 0, 10, 10]

type PlateProps = {
  index: number
  progress: MotionValue< number>
  children: ReactNode
}

const Plate = ({ index, progress, children }: PlateProps) => {
  // Hooks
  const y = useTransform(progress, v => plateY(index, v))
  const scale = useTransform(progress, v => plateScale(index, v))
  const apart = useTransform(progress, v => plateProgress(index, v))
  const shadow = useTransform(apart, v => v * 0.16)
  const opacity = useTransform(apart, [0, 0.08], [0, 1])

  // Vars
  const slab = PLATE_SLABS[index]
  const skirt = SKIRT[index]

  return (
    <m.g style={{ y, scale, opacity }}>
      {/* A soft shadow the plate casts on the one below it as they part. */}
      {index > 0 && (
        <m.path
          d={topPath(slab, -6, CX, slab.depth + 34)}
          fill={H('6E6556')}
          filter='url(#n3-blur-spill)'
          style={{ opacity: shadow }}
        />
      )}
      {skirt > 0 && (
        <m.g style={{ opacity: apart }}>
          <SlabBody slab={{ ...slab, top: slab.top + slab.depth - 1, depth: skirt + 1 }} />
        </m.g>
      )}
      {children}
    </m.g>
  )
}

type PlateNumberProps = { slab: SlabType; n: number; main?: boolean }

/**
 * The stage number engraved into a plate's top face near its front corner, laid flat in the
 * surface so it reads as part of the ceramic: a soft groove with light catching its lower lip.
 */
const PlateNumber = ({ slab, n, main = false }: PlateNumberProps) => {
  // Vars
  const k = slab.half * 0.56
  const [x, y] = project(k, k, CX, slab.top)
  const plane = `matrix(${FACE_MATRIX.top} 0 0) rotate(-45)`
  const label = String(n).padStart(2, '0')

  const text = {
    textAnchor: 'middle' as const,
    dominantBaseline: 'central' as const,
    fontSize: 25,
    fontWeight: 500,
    letterSpacing: 2.5
  }

  return (
    <g transform={`translate(${x.toFixed(2)} ${y.toFixed(2)})`} aria-hidden>
      <text {...text} transform={`translate(0 0.9) ${plane}`} fill={H('FFFFFF')} opacity='0.95'>
        {label}
      </text>
      <text {...text} transform={plane} fill={main ? H('FF8C4D') : H('CBC6BC')}>
        {label}
      </text>
    </g>
  )
}

// A little light coming up on the AI plate: a ring and core in its plane.
const AiEmphasis = ({ progress }: { progress: MotionValue< number> }) => {
  // Hooks
  const on = useTransform(progress, v => clamp2((v - 0.5) / 0.4))
  const rim = useTransform(on, v => v * 0.9)
  const halo = useTransform(on, v => v * 0.55)
  const spill = useTransform(halo, v => v * 0.35)

  return (
    <g transform={`translate(${CX} ${MID.top}) matrix(${FACE_MATRIX.top} 0 0)`}>
      <m.circle r='86' fill={ORANGE3} filter='url(#n3-blur-spill)' style={{ opacity: spill }} />
      <m.circle r='56' fill='none' stroke={ORANGE3} strokeWidth='1.4' style={{ opacity: rim }} />
      <m.circle r='38' fill='none' stroke={ORANGE3} strokeWidth='1' strokeDasharray='3 6' style={{ opacity: halo }} />
      <m.circle r='9' fill={ORANGE3} style={{ opacity: rim }} />
    </g>
  )
}

type SyntraExplodedCoreProps = SVGProps< SVGSVGElement> & { progress: MotionValue< number> }

/**
 * The core taken apart into its five stage plates, driven by `progress`. The AI plate's underglow
 * strengthens as the stack opens, on top of the resting glow.
 */
const SyntraExplodedCore = ({ progress, ...props }: SyntraExplodedCoreProps) => {
  // Hooks
  const strongGlow = useTransform(progress, v => clamp2((v - 0.4) / 0.5) * 0.6)

  // Vars
  const seam = { half: MID.half - 2, radius: MID.radius - 2, top: MID.top + MID.depth - 1, depth: SEAM_GAP + 1 }

  const inlay = (slab: (typeof PLATE_SLABS)[number]) => (
    <>
      <path d={topPath(slab, 19)} fill='url(#n3-inlay)' stroke={H('DDD9D1')} strokeWidth='1.2' />
      <path d={topPath(slab, 20.5)} fill='none' stroke={H('fff')} strokeWidth='1' opacity='0.9' />
    </>
  )

  return (
    <svg viewBox='0 0 1000 1000' aria-hidden {...props}>
      <CoreDefs />

      <Plate index={0} progress={progress}>
        <SlabBody slab={PLATE_SLABS[0]} />
        <SlabTop slab={PLATE_SLABS[0]} groove={10} />
        <PlateNumber slab={PLATE_SLABS[0]} n={1} />
        <FaceMark slab={BASE} side='right' u={0.5} v={0.62} w={4.5} h={4.5} fill='url(#n3-slot)' />
        <FaceMark slab={BASE} side='right' u={0.58} v={0.62} w={4.5} h={4.5} fill='url(#n3-slot)' />
        <FaceMark slab={BASE} side='right' u={0.66} v={0.62} w={4.5} h={4.5} fill='url(#n3-slot)' />
        <FaceMark slab={BASE} side='left' u={0.32} v={0.62} w={4.5} h={4.5} fill='url(#n3-slot)' />
        <FaceMark slab={BASE} side='left' u={0.4} v={0.62} w={4.5} h={4.5} fill='url(#n3-slot)' />
      </Plate>

      <Plate index={1} progress={progress}>
        <SlabBody slab={PLATE_SLABS[1]} />
        <SlabTop slab={PLATE_SLABS[1]} groove={10} />
        <PlateNumber slab={PLATE_SLABS[1]} n={2} />
        <PanelLine slab={BASE} side='left' v={0.3} />
        <PanelLine slab={BASE} side='right' v={0.3} />
      </Plate>

      <Plate index={AI_PLATE} progress={progress}>
        <g className='n3-seam-pulse'>
          <path d={topPath(MID, 4, CX, BASE.top - MID.top)} fill={ORANGE3} opacity='0.24' filter='url(#n3-blur-spill)' />
          <path d={bodyPath(seam)} fill={ORANGE3} opacity='0.6' filter='url(#n3-blur-glow)' />
        </g>
        <m.path d={bodyPath(seam)} fill={ORANGE3} filter='url(#n3-blur-glow)' style={{ opacity: strongGlow }} />
        <path d={bodyPath(seam)} fill='url(#n3-seam)' />
        <SlabBody slab={MID} />
        <SlabTop slab={MID} groove={11} />
        <PlateNumber slab={MID} n={3} main />
        <PanelLine slab={MID} side='left' v={0.78} />
        <PanelLine slab={MID} side='right' v={0.78} />
        <FaceMark slab={MID} side='left' u={0.62} v={0.42} w={34} h={7.5} fill='url(#n3-slot)' />
        <FaceMark slab={MID} side='left' u={0.34} v={0.42} w={5} h={5} fill='url(#n3-slot)' />
        <FaceMark slab={MID} side='left' u={0.42} v={0.42} w={5} h={5} fill='url(#n3-slot)' />
        <FaceMark slab={MID} side='right' u={0.44} v={0.42} w={5} h={5} fill='url(#n3-slot)' />
        <FaceMark slab={MID} side='right' u={0.52} v={0.42} w={5} h={5} fill='url(#n3-slot)' />
        <FaceMark slab={MID} side='right' u={0.68} v={0.42} w={22} h={4.5} fill='url(#n3-slot)' />
        <g className='n3-seam-pulse'>
          <FaceMark slab={MID} side='left' u={0.2} v={0.42} w={7} h={2.6} fill={ORANGE3} glow />
        </g>
        <Fastener slab={MID} x={0} y={-MID.half + 28} />
        <Fastener slab={MID} x={-MID.half + 28} y={0} />
        <Fastener slab={MID} x={MID.half - 28} y={0} />
        <Fastener slab={MID} x={0} y={MID.half - 28} />
        <AiEmphasis progress={progress} />
      </Plate>

      <Plate index={3} progress={progress}>
        <SlabBody slab={PLATE_SLABS[3]} />
        <SlabTop slab={PLATE_SLABS[3]} />
        {inlay(PLATE_SLABS[3])}
        <PlateNumber slab={PLATE_SLABS[3]} n={4} />
      </Plate>

      <Plate index={4} progress={progress}>
        <SlabBody slab={PLATE_SLABS[4]} />
        <SlabTop slab={PLATE_SLABS[4]} />
        {inlay(CAP)}
        <CoreLogo x={CX} y={CAP.top} />
      </Plate>
    </svg>
  )
}

// ───── assets/motionuikit/svg/syntra-orbital-system.tsx ─────
// Two orbits around the core. Their nodes ride them in opposite directions (see syntra-particles).
const SyntraOrbitalSystem = (props: SVGProps< SVGSVGElement>) => {
  return (
    <svg viewBox='0 0 1000 1000' aria-hidden {...props}>
      <g fill='none' stroke={H('D9D6D0')} strokeWidth='1'>
        <ellipse cx={CX} cy={CY} rx={RING_RX[0]} ry={RING_RX[0] * RING_FLATTEN} opacity='0.8' />
        <ellipse cx={CX} cy={CY} rx={RING_RX[1]} ry={RING_RX[1] * RING_FLATTEN} opacity='0.65' />
      </g>
    </svg>
  )
}

// ───── assets/motionuikit/svg/syntra-particles.tsx ─────
type NodeType = { ring: 0 | 1; angle: number; size: number }

/**
 * Small crystal beads spaced evenly around each orbit: four on the inner, five on the outer. Every
 * bead on an orbit shares its lap time, so they keep their spacing; the inner orbit runs
 * clockwise and the outer one against it.
 */
const NODES2: NodeType[] = [
  { ring: 0, angle: 20, size: 7 },
  { ring: 0, angle: 110, size: 5.5 },
  { ring: 0, angle: 200, size: 8 },
  { ring: 0, angle: 290, size: 6 },
  { ring: 1, angle: 60, size: 6 },
  { ring: 1, angle: 132, size: 8 },
  { ring: 1, angle: 204, size: 5.5 },
  { ring: 1, angle: 276, size: 7 },
  { ring: 1, angle: 348, size: 6.5 }
]

/**
 * A small sphere of amber glass: light at the upper left deepening to orange at the lower right,
 * a rim that is bright on top and darker below, a crisp specular highlight, and light gathered
 * at the bottom where it passes through the glass.
 */
const Crystal = ({ size }: { size: number }) => {
  return (
    <g filter='url(#n3-crystal-shadow)'>
      <circle r={size * 1.55} fill='url(#n3-crystal-halo)' />
      <circle r={size} fill='url(#n3-crystal-body)' />
      <circle r={size} fill='url(#n3-crystal-refract)' />
      <circle r={size - 0.5} fill='none' stroke='url(#n3-crystal-rim)' strokeWidth='1' />
      <ellipse
        cx={-size * 0.32}
        cy={-size * 0.4}
        rx={size * 0.36}
        ry={size * 0.2}
        transform={`rotate(-30 ${-size * 0.32} ${-size * 0.4})`}
        fill={H('FFFFFF')}
        opacity='0.95'
      />
      <circle cx={size * 0.34} cy={-size * 0.5} r={Math.max(0.7, size * 0.07)} fill={H('FFFFFF')} opacity='0.85' />
    </g>
  )
}

/**
 * The nodes are drawn twice, once behind the core and once in front, each clipped to its half of
 * the artboard, so a node passing the back of an orbit slips behind the object instead of over it.
 * `paused` freezes the beads where they are; they carry on from there when it clears.
 */
type SyntraParticlesProps = SVGProps< SVGSVGElement> & { half: 'back' | 'front'; animate: boolean; paused?: boolean }

const SyntraParticles = ({ half, animate, paused = false, ...props }: SyntraParticlesProps) => {
  // Refs
  const ref = useRef< SVGSVGElement>(null)

  // Vars
  const clipId = `n3-clip-${half}`

  // The beads run on this SVG's own animation clock, so pausing the clock stops them all at once.
  useEffect(() => {
    const svg = ref.current

    if (!svg) return

    if (paused) svg.pauseAnimations?.()
    else svg.unpauseAnimations?.()
  }, [paused])

  return (
    <svg ref={ref} viewBox='0 0 1000 1000' aria-hidden {...props}>
      <defs>
        <clipPath id={clipId}>
          {half === 'back' ? (
            <rect x='-200' y='-200' width='1400' height={CY + 200} />
          ) : (
            <rect x='-200' y={CY} width='1400' height='700' />
          )}
        </clipPath>
        <linearGradient id='n3-crystal-body' x1='0.15' y1='0.1' x2='0.85' y2='0.95'>
          <stop offset='0' stopColor={H('FFE7D8')} stopOpacity='0.9' />
          <stop offset='0.5' stopColor={H('FFA56E')} stopOpacity='0.88' />
          <stop offset='1' stopColor={H('F2661F')} stopOpacity='0.95' />
        </linearGradient>
        <radialGradient id='n3-crystal-refract' cx='0.6' cy='0.78' r='0.45'>
          <stop offset='0' stopColor={H('FFF4EC')} stopOpacity='0.85' />
          <stop offset='1' stopColor={H('FFF4EC')} stopOpacity='0' />
        </radialGradient>
        <linearGradient id='n3-crystal-rim' x1='0' y1='0' x2='0' y2='1'>
          <stop offset='0' stopColor={H('FFFFFF')} stopOpacity='0.95' />
          <stop offset='1' stopColor={H('C94F12')} stopOpacity='0.55' />
        </linearGradient>
        <radialGradient id='n3-crystal-halo' cx='0.5' cy='0.5' r='0.5'>
          <stop offset='0.5' stopColor={H('FF7A2F')} stopOpacity='0.12' />
          <stop offset='1' stopColor={H('FF7A2F')} stopOpacity='0' />
        </radialGradient>
        <filter id='n3-crystal-shadow' x='-80%' y='-80%' width='260%' height='260%'>
          <feDropShadow dx='0' dy='2.5' stdDeviation='2.5' floodColor={H('9A4A1E')} floodOpacity='0.25' />
        </filter>
      </defs>

      <g clipPath={`url(#${clipId})`}>
        {NODES2.map((node, i) => {
          const rx = RING_RX[node.ring]
          const [x, y] = ringPoint(rx, node.angle)
          const dur = MOTION.orbitSeconds[node.ring]
          const reverse = node.ring === 1

          // The ring path starts at angle 0 and runs clockwise; a reversed lap starts from the far end.
          const start = node.angle / 360
          const begin = -dur * (reverse ? 1 - start : start)

          return (
            <g key={i} transform={animate ? undefined : `translate(${x.toFixed(1)} ${y.toFixed(1)})`}>
              <Crystal size={node.size} />
              {animate && (
                <animateMotion
                  dur={`${dur}s`}
                  begin={`${begin}s`}
                  repeatCount='indefinite'
                  path={ringPath(rx)}
                  {...(reverse && { keyPoints: '1;0', keyTimes: '0;1', calcMode: 'linear' })}
                />
              )}
            </g>
          )
        })}
      </g>
    </svg>
  )
}

// ───── assets/motionuikit/svg/syntra-technical-backdrop.tsx ─────
/**
 * Construction geometry behind the core: quiet enough that the eye only finds it on a second look.
 * Deterministic scatter, so server and client render the same dots.
 */
const DOTS = Array.from({ length: 26 }, (_, i) => {
  const a = (i * 137.5 * Math.PI) / 180
  const r = 160 + ((i * 53) % 340)

  return [
    round(CX + r * Math.cos(a) * 1.02),
    round(CY + r * Math.sin(a) * RING_FLATTEN * 1.6),
    0.8 + (i % 3) * 0.5
  ] as const
})

const SyntraTechnicalBackdrop = (props: SVGProps< SVGSVGElement>) => {
  return (
    <svg viewBox='0 0 1000 1000' aria-hidden {...props}>
      <defs>
        <linearGradient id='n3-axis-v' x1='0' y1='0' x2='0' y2='1'>
          <stop offset='0' stopColor={H('C9C5BC')} stopOpacity='0' />
          <stop offset='0.5' stopColor={H('C9C5BC')} stopOpacity='0.45' />
          <stop offset='1' stopColor={H('C9C5BC')} stopOpacity='0' />
        </linearGradient>
        <linearGradient id='n3-axis-h' x1='0' y1='0' x2='1' y2='0'>
          <stop offset='0' stopColor={H('C9C5BC')} stopOpacity='0' />
          <stop offset='0.5' stopColor={H('C9C5BC')} stopOpacity='0.7' />
          <stop offset='1' stopColor={H('C9C5BC')} stopOpacity='0' />
        </linearGradient>
      </defs>

      <rect x={CX - 0.5} y='110' width='1' height='780' fill='url(#n3-axis-v)' />
      <rect x='70' y={CY - 0.5} width='860' height='1' fill='url(#n3-axis-h)' />

      <g fill={H('C9C5BC')}>
        {DOTS.map(([x, y, r], i) => (
          <circle key={i} cx={x} cy={y} r={r} opacity={0.35 + (i % 4) * 0.12} />
        ))}
      </g>

      <g stroke={H('C9C5BC')} strokeWidth='1' opacity='0.5'>
        {(
          [
            [212, 292],
            [790, 690],
            [860, 430],
            [150, 560]
          ] as const
        ).map(([x, y]) => (
          <path key={`${x}-${y}`} d={`M${x - 5} ${y}h10M${x} ${y - 5}v10`} />
        ))}
      </g>
    </svg>
  )
}

// ───── assets/motionuikit/svg/syntra-airtable-logo.tsx ─────
// The Airtable brand mark, from Iconify's open logo sets, in its own colours.
const SyntraAirtableLogo = (props: SVGProps< SVGSVGElement>) => {
  return (
    <svg viewBox='0 0 256 215' aria-hidden {...props}>
      <path
        fill={H('ffbf00')}
        d='M114.259 2.701L18.86 42.176c-5.305 2.195-5.25 9.73.089 11.847l95.797 37.989a35.54 35.54 0 0 0 26.208 0l95.799-37.99c5.337-2.115 5.393-9.65.086-11.846L141.442 2.7a35.55 35.55 0 0 0-27.183 0'
      />
      <path
        fill={H('26b5f8')}
        d='M136.35 112.757v94.902c0 4.514 4.55 7.605 8.746 5.942l106.748-41.435a6.39 6.39 0 0 0 4.035-5.941V71.322c0-4.514-4.551-7.604-8.747-5.941l-106.748 41.434a6.39 6.39 0 0 0-4.035 5.942'
      />
      <path
        fill={H('ed3049')}
        d='m111.423 117.654l-31.68 15.296l-3.217 1.555L9.65 166.548C5.411 168.593 0 165.504 0 160.795V71.72c0-1.704.874-3.175 2.046-4.283a7.3 7.3 0 0 1 1.618-1.213c1.598-.959 3.878-1.215 5.816-.448l101.41 40.18c5.155 2.045 5.56 9.268.533 11.697'
      />
      <path
        fillOpacity='.25'
        d='m111.423 117.654l-31.68 15.296L2.045 67.438a7.3 7.3 0 0 1 1.618-1.213c1.598-.959 3.878-1.215 5.816-.448l101.41 40.18c5.155 2.045 5.56 9.268.533 11.697'
      />
    </svg>
  )
}

// ───── assets/motionuikit/svg/syntra-claude-logo.tsx ─────
// The Claude brand mark, from Iconify's open logo sets, in its own colours.
const SyntraClaudeLogo = (props: SVGProps< SVGSVGElement>) => {
  return (
    <svg viewBox='0 0 256 257' aria-hidden {...props}>
      <path
        fill={H('d97757')}
        d='m50.228 170.321l50.357-28.257l.843-2.463l-.843-1.361h-2.462l-8.426-.518l-28.775-.778l-24.952-1.037l-24.175-1.296l-6.092-1.297L0 125.796l.583-3.759l5.12-3.434l7.324.648l16.202 1.101l24.304 1.685l17.629 1.037l26.118 2.722h4.148l.583-1.685l-1.426-1.037l-1.101-1.037l-25.147-17.045l-27.22-18.017l-14.258-10.37l-7.713-5.25l-3.888-4.925l-1.685-10.758l7-7.713l9.397.649l2.398.648l9.527 7.323l20.35 15.75L94.817 91.9l3.889 3.24l1.555-1.102l.195-.777l-1.75-2.917l-14.453-26.118l-15.425-26.572l-6.87-11.018l-1.814-6.61c-.648-2.723-1.102-4.991-1.102-7.778l7.972-10.823L71.42 0l10.63 1.426l4.472 3.888l6.61 15.101l10.694 23.786l16.591 32.34l4.861 9.592l2.592 8.879l.973 2.722h1.685v-1.556l1.36-18.211l2.528-22.36l2.463-28.776l.843-8.1l4.018-9.722l7.971-5.25l6.222 2.981l5.12 7.324l-.713 4.73l-3.046 19.768l-5.962 30.98l-3.889 20.739h2.268l2.593-2.593l10.499-13.934l17.628-22.036l7.778-8.749l9.073-9.657l5.833-4.601h11.018l8.1 12.055l-3.628 12.443l-11.342 14.388l-9.398 12.184l-13.48 18.147l-8.426 14.518l.778 1.166l2.01-.194l30.46-6.481l16.462-2.982l19.637-3.37l8.88 4.148l.971 4.213l-3.5 8.62l-20.998 5.184l-24.628 4.926l-36.682 8.685l-.454.324l.519.648l16.526 1.555l7.065.389h17.304l32.21 2.398l8.426 5.574l5.055 6.805l-.843 5.184l-12.962 6.611l-17.498-4.148l-40.83-9.721l-14-3.5h-1.944v1.167l11.666 11.406l21.387 19.314l26.767 24.887l1.36 6.157l-3.434 4.86l-3.63-.518l-23.526-17.693l-9.073-7.972l-20.545-17.304h-1.36v1.814l4.73 6.935l25.017 37.59l1.296 11.536l-1.814 3.76l-6.481 2.268l-7.13-1.297l-14.647-20.544l-15.1-23.138l-12.185-20.739l-1.49.843l-7.194 77.448l-3.37 3.953l-7.778 2.981l-6.48-4.925l-3.436-7.972l3.435-15.749l4.148-20.544l3.37-16.333l3.046-20.285l1.815-6.74l-.13-.454l-1.49.194l-15.295 20.999l-23.267 31.433l-18.406 19.702l-4.407 1.75l-7.648-3.954l.713-7.064l4.277-6.286l25.47-32.405l15.36-20.092l9.917-11.6l-.065-1.686h-.583L44.07 198.125l-12.055 1.555l-5.185-4.86l.648-7.972l2.463-2.593l20.35-13.999z'
      />
    </svg>
  )
}

// ───── assets/motionuikit/svg/syntra-gemini-logo.tsx ─────
// The Gemini brand mark, from Iconify's open logo sets, in its own colours.
const SyntraGeminiLogo = (props: SVGProps< SVGSVGElement>) => {
  return (
    <svg viewBox='0 0 16 16' aria-hidden {...props}>
      <path
        fill={H('448aff')}
        d='M15 8.014A7.457 7.457 0 0 0 8.014 15h-.028A7.456 7.456 0 0 0 1 8.014v-.028A7.456 7.456 0 0 0 7.986 1h.028A7.457 7.457 0 0 0 15 7.986z'
      />
    </svg>
  )
}

// ───── assets/motionuikit/svg/syntra-github-logo.tsx ─────
// The GitHub brand mark, from Iconify's open logo sets, in its own colours.
const SyntraGithubLogo = (props: SVGProps< SVGSVGElement>) => {
  return (
    <svg viewBox='0 0 256 250' aria-hidden {...props}>
      <path
        fill={H('161614')}
        d='M128.001 0C57.317 0 0 57.307 0 128.001c0 56.554 36.676 104.535 87.535 121.46c6.397 1.185 8.746-2.777 8.746-6.158c0-3.052-.12-13.135-.174-23.83c-35.61 7.742-43.124-15.103-43.124-15.103c-5.823-14.795-14.213-18.73-14.213-18.73c-11.613-7.944.876-7.78.876-7.78c12.853.902 19.621 13.19 19.621 13.19c11.417 19.568 29.945 13.911 37.249 10.64c1.149-8.272 4.466-13.92 8.127-17.116c-28.431-3.236-58.318-14.212-58.318-63.258c0-13.975 5-25.394 13.188-34.358c-1.329-3.224-5.71-16.242 1.24-33.874c0 0 10.749-3.44 35.21 13.121c10.21-2.836 21.16-4.258 32.038-4.307c10.878.049 21.837 1.47 32.066 4.307c24.431-16.56 35.165-13.12 35.165-13.12c6.967 17.63 2.584 30.65 1.255 33.873c8.207 8.964 13.173 20.383 13.173 34.358c0 49.163-29.944 59.988-58.447 63.157c4.591 3.972 8.682 11.762 8.682 23.704c0 17.126-.148 30.91-.148 35.126c0 3.407 2.304 7.398 8.792 6.14C219.37 232.5 256 184.537 256 128.002C256 57.307 198.691 0 128.001 0m-80.06 182.34c-.282.636-1.283.827-2.194.39c-.929-.417-1.45-1.284-1.15-1.922c.276-.655 1.279-.838 2.205-.399c.93.418 1.46 1.293 1.139 1.931m6.296 5.618c-.61.566-1.804.303-2.614-.591c-.837-.892-.994-2.086-.375-2.66c.63-.566 1.787-.301 2.626.591c.838.903 1 2.088.363 2.66m4.32 7.188c-.785.545-2.067.034-2.86-1.104c-.784-1.138-.784-2.503.017-3.05c.795-.547 2.058-.055 2.861 1.075c.782 1.157.782 2.522-.019 3.08m7.304 8.325c-.701.774-2.196.566-3.29-.49c-1.119-1.032-1.43-2.496-.726-3.27c.71-.776 2.213-.558 3.315.49c1.11 1.03 1.45 2.505.701 3.27m9.442 2.81c-.31 1.003-1.75 1.459-3.199 1.033c-1.448-.439-2.395-1.613-2.103-2.626c.301-1.01 1.747-1.484 3.207-1.028c1.446.436 2.396 1.602 2.095 2.622m10.744 1.193c.036 1.055-1.193 1.93-2.715 1.95c-1.53.034-2.769-.82-2.786-1.86c0-1.065 1.202-1.932 2.733-1.958c1.522-.03 2.768.818 2.768 1.868m10.555-.405c.182 1.03-.875 2.088-2.387 2.37c-1.485.271-2.861-.365-3.05-1.386c-.184-1.056.893-2.114 2.376-2.387c1.514-.263 2.868.356 3.061 1.403'
      />
    </svg>
  )
}

// ───── assets/motionuikit/svg/syntra-google-analytics-logo.tsx ─────
// The Google Analytics brand mark, from Iconify's open logo sets, in its own colours.
const SyntraGoogleAnalyticsLogo = (props: SVGProps< SVGSVGElement>) => {
  return (
    <svg viewBox='0 0 256 284' aria-hidden {...props}>
      <path
        fill={H('f9ab00')}
        d='M256.003 247.933a35.224 35.224 0 0 1-39.376 35.161c-18.044-2.67-31.266-18.371-30.826-36.606V36.845C185.365 18.591 198.62 2.881 216.687.24a35.22 35.22 0 0 1 39.316 35.16z'
      />
      <path
        fill={H('e37400')}
        d='M35.101 213.193c19.386 0 35.101 15.716 35.101 35.101c0 19.386-15.715 35.101-35.101 35.101S0 267.68 0 248.295s15.715-35.102 35.101-35.102m92.358-106.387c-19.477 1.068-34.59 17.406-34.137 36.908v94.285c0 25.588 11.259 41.122 27.755 44.433a35.16 35.16 0 0 0 42.146-34.56V142.089a35.22 35.22 0 0 0-35.764-35.282'
      />
    </svg>
  )
}

// ───── assets/motionuikit/svg/syntra-google-drive-logo.tsx ─────
/**
 * The Google Drive brand mark, from Iconify's open logo sets, in its own colours. Its ids are
 * per instance: the same logo appears in hidden and visible cards at once.
 */
const SyntraGoogleDriveLogo = (props: SVGProps< SVGSVGElement>) => {
  // Hooks
  const uid = useId().replace(/[^\w-]/g, '')

  return (
    <svg viewBox='0 0 256 238' aria-hidden {...props}>
      <defs>
        <linearGradient id={`${uid}SVGjzk6ueva`} x1='86.924%' x2='7.63%' y1='94.294%' y2='45.952%'>
          <stop offset='9%' stopColor={H('ffe921')} />
          <stop offset='100%' stopColor={H('fec700')} />
        </linearGradient>
        <linearGradient id={`${uid}SVGb0f7AcFJ`} x1='99.538%' x2='23.437%' y1='93.901%' y2='54.033%'>
          <stop offset='15%' stopColor={H('a9a8ff')} />
          <stop offset='33%' stopColor={H('6d97ff')} />
          <stop offset='48%' stopColor={H('3186ff')} />
        </linearGradient>
        <linearGradient id={`${uid}SVGxMwz6dYk`} x1='87.128%' x2='-.639%' y1='51.518%' y2='93.078%'>
          <stop offset='55%' stopColor={H('0ebc5f')} />
          <stop offset='85%' stopColor={H('78c9ff')} />
        </linearGradient>
        <path
          id={`${uid}SVGAiIt3btS`}
          d='M77.303 29.27c22.531-39.026 78.863-39.027 101.394 0l69.373 120.158c22.531 39.027-5.633 87.81-50.698 87.81H58.628c-45.065 0-73.23-48.783-50.698-87.81z'
        />
      </defs>
      <mask id={`${uid}SVG6FLXsceq`} fill={H('fff')}>
        <use href={`#${uid}SVGAiIt3btS`} />
      </mask>
      <g mask={`url(#${uid}SVG6FLXsceq)`}>
        <path
          fill={`url(#${uid}SVGjzk6ueva)`}
          d='M341.719 295.937H200.166l-29.293-50.735l70.777-122.589z'
          transform='translate(-42.87 -58.67)'
        />
        <path
          fill={`url(#${uid}SVGb0f7AcFJ)`}
          d='m0 295.916l100.069-173.325v.003l-29.282 50.723h58.57l70.783 122.596l-200.138.001z'
          transform='translate(-42.87 -58.67)'
        />
        <path
          fill={`url(#${uid}SVGxMwz6dYk)`}
          d='m170.881 0l70.781 122.6l-29.286 50.726H70.812z'
          transform='translate(-42.87 -58.67)'
        />
      </g>
    </svg>
  )
}

// ───── assets/motionuikit/svg/syntra-google-sheets-logo.tsx ─────
/**
 * The Google Sheets brand mark, from Iconify's open logo sets, in its own colours. Its ids are
 * per instance: the same logo appears in hidden and visible cards at once.
 */
const SyntraGoogleSheetsLogo = (props: SVGProps< SVGSVGElement>) => {
  // Hooks
  const uid = useId().replace(/[^\w-]/g, '')

  return (
    <svg viewBox='0 0 74 100' aria-hidden {...props}>
      <defs>
        <path
          id={`${uid}SVGIGBbGcaJ`}
          fill={H('fff')}
          d='M45.398 1.43H7.867c-3.643 0-6.623 2.98-6.623 6.624v83.893c0 3.642 2.98 6.623 6.623 6.623h57.4c3.643 0 6.624-2.98 6.624-6.623V27.923z'
        />
      </defs>
      <mask
        id={`${uid}SVGeu8TmevZ`}
        width='71'
        height='98'
        x='1'
        y='1'
        maskUnits='userSpaceOnUse'
        style={{ maskType: 'alpha' }}
      >
        <use href={`#${uid}SVGIGBbGcaJ`} />
      </mask>
      <g fill='none'>
        <g mask={`url(#${uid}SVGeu8TmevZ)`}>
          <path
            fill={H('0f9d58')}
            d='M45.398 1.43H7.867c-3.643 0-6.623 2.98-6.623 6.624v83.893c0 3.642 2.98 6.623 6.623 6.623h57.4c3.643 0 6.624-2.98 6.624-6.623V27.923L56.436 16.884z'
          />
        </g>
        <g mask={`url(#${uid}SVGeu8TmevZ)`}>
          <path
            fill={H('f1f1f1')}
            d='M18.905 48.896v32.012H54.23V48.896zM34.36 76.493H23.321v-5.52h11.038zm0-8.831H23.321v-5.52h11.038zm0-8.831H23.321v-5.52h11.038zm15.454 17.662H38.775v-5.52h11.038zm0-8.831H38.775v-5.52h11.038zm0-8.831H38.775v-5.52h11.038z'
          />
        </g>
        <g mask={`url(#${uid}SVGeu8TmevZ)`}>
          <path fill={`url(#${uid}SVGoPMCPXuN)`} d='m47.335 25.986l24.556 24.55V27.922z' />
        </g>
        <g mask={`url(#${uid}SVGeu8TmevZ)`}>
          <path fill={H('87ceac')} d='M45.398 1.43V21.3a6.62 6.62 0 0 0 6.623 6.623h19.87z' />
        </g>
        <g mask={`url(#${uid}SVGeu8TmevZ)`}>
          <path
            fill={H('fff')}
            fillOpacity='.2'
            d='M7.867 1.43c-3.643 0-6.623 2.98-6.623 6.624v.551c0-3.642 2.98-6.623 6.623-6.623h37.531V1.43z'
          />
        </g>
        <g mask={`url(#${uid}SVGeu8TmevZ)`}>
          <path
            fill={H('263238')}
            fillOpacity='.2'
            d='M65.267 98.018h-57.4c-3.643 0-6.623-2.98-6.623-6.623v.552c0 3.642 2.98 6.623 6.623 6.623h57.4c3.643 0 6.624-2.98 6.624-6.623v-.552c0 3.642-2.98 6.623-6.624 6.623'
          />
        </g>
        <g mask={`url(#${uid}SVGeu8TmevZ)`}>
          <path
            fill={H('263238')}
            fillOpacity='.1'
            d='M52.021 27.923a6.62 6.62 0 0 1-6.623-6.623v.552a6.62 6.62 0 0 0 6.623 6.623h19.87v-.552z'
          />
        </g>
        <path
          fill={`url(#${uid}SVGXgG8ibSI)`}
          d='M45.398 1.43H7.867c-3.643 0-6.623 2.98-6.623 6.624v83.893c0 3.642 2.98 6.623 6.623 6.623h57.4c3.643 0 6.624-2.98 6.624-6.623V27.923z'
        />
        <defs>
          <radialGradient
            id={`${uid}SVGXgG8ibSI`}
            cx='0'
            cy='0'
            r='1'
            gradientTransform='translate(3.482 3.361)scale(113.917)'
            gradientUnits='userSpaceOnUse'
          >
            <stop stopColor={H('fff')} stopOpacity='.1' />
            <stop offset='1' stopColor={H('fff')} stopOpacity='0' />
          </radialGradient>
          <linearGradient
            id={`${uid}SVGoPMCPXuN`}
            x1='59.614'
            x2='59.614'
            y1='28.093'
            y2='50.539'
            gradientUnits='userSpaceOnUse'
          >
            <stop stopColor={H('263238')} stopOpacity='.2' />
            <stop offset='1' stopColor={H('263238')} stopOpacity='.02' />
          </linearGradient>
        </defs>
      </g>
    </svg>
  )
}

// ───── assets/motionuikit/svg/syntra-google-slides-logo.tsx ─────
/**
 * The Google Slides brand mark, from Iconify's open logo sets, in its own colours. Its ids are
 * per instance: the same logo appears in hidden and visible cards at once.
 */
const SyntraGoogleSlidesLogo = (props: SVGProps< SVGSVGElement>) => {
  // Hooks
  const uid = useId().replace(/[^\w-]/g, '')

  return (
    <svg viewBox='0 0 73 100' aria-hidden {...props}>
      <defs>
        <path
          id={`${uid}SVGLqcj4blC`}
          fill={H('fff')}
          d='M44.823 0h-38.1C3.026 0 0 3.026 0 6.723v85.165c0 3.697 3.026 6.723 6.723 6.723h58.27c3.699 0 6.724-3.026 6.724-6.723V26.894z'
        />
      </defs>
      <mask
        id={`${uid}SVGOJaYrdyS`}
        width='72'
        height='99'
        x='0'
        y='0'
        maskUnits='userSpaceOnUse'
        style={{ maskType: 'alpha' }}
      >
        <use href={`#${uid}SVGLqcj4blC`} />
      </mask>
      <g fill='none'>
        <g mask={`url(#${uid}SVGOJaYrdyS)`}>
          <path
            fill={H('f4b400')}
            d='M44.823 0h-38.1C3.026 0 0 3.026 0 6.723v85.165c0 3.697 3.026 6.723 6.723 6.723h58.27c3.699 0 6.724-3.026 6.724-6.723V26.894L56.03 15.688z'
          />
        </g>
        <g mask={`url(#${uid}SVGOJaYrdyS)`}>
          <path
            fill={H('f1f1f1')}
            d='M50.426 44.823H21.291a3.37 3.37 0 0 0-3.362 3.362V77.32a3.37 3.37 0 0 0 3.362 3.362h29.135a3.37 3.37 0 0 0 3.362-3.362V48.185a3.37 3.37 0 0 0-3.362-3.362m-1.12 25.774H22.412V54.909h26.894z'
          />
        </g>
        <g mask={`url(#${uid}SVGOJaYrdyS)`}>
          <path fill={`url(#${uid}SVGHjkXJesw)`} d='M46.79 24.927L71.717 49.85V26.894z' />
        </g>
        <g mask={`url(#${uid}SVGOJaYrdyS)`}>
          <path fill={H('fada80')} d='M44.823 0v20.17a6.72 6.72 0 0 0 6.724 6.724h20.17z' />
        </g>
        <g mask={`url(#${uid}SVGOJaYrdyS)`}>
          <path fill={H('fff')} fillOpacity='.1' d='M44.823 0v.56l26.334 26.334h.56z' />
        </g>
        <g mask={`url(#${uid}SVGOJaYrdyS)`}>
          <path
            fill={H('fff')}
            fillOpacity='.2'
            d='M6.723 0C3.026 0 0 3.026 0 6.723v.56C0 3.587 3.026.56 6.723.56h38.1V0z'
          />
        </g>
        <g mask={`url(#${uid}SVGOJaYrdyS)`}>
          <path
            fill={H('bf360c')}
            fillOpacity='.2'
            d='M64.994 98.05H6.724C3.025 98.05 0 95.026 0 91.328v.56c0 3.698 3.026 6.724 6.723 6.724h58.27c3.699 0 6.724-3.026 6.724-6.723v-.56c0 3.697-3.025 6.723-6.723 6.723'
          />
        </g>
        <g mask={`url(#${uid}SVGOJaYrdyS)`}>
          <path
            fill={H('bf360c')}
            fillOpacity='.1'
            d='M51.547 26.894a6.72 6.72 0 0 1-6.724-6.723v.56a6.72 6.72 0 0 0 6.724 6.723h20.17v-.56z'
          />
        </g>
        <path
          fill={`url(#${uid}SVG6dpJadoi)`}
          d='M44.823 0h-38.1C3.026 0 0 3.026 0 6.723v85.165c0 3.697 3.026 6.723 6.723 6.723h58.27c3.699 0 6.724-3.026 6.724-6.723V26.894z'
        />
        <defs>
          <radialGradient
            id={`${uid}SVG6dpJadoi`}
            cx='0'
            cy='0'
            r='1'
            gradientTransform='translate(2.272 1.96)scale(115.643)'
            gradientUnits='userSpaceOnUse'
          >
            <stop stopColor={H('fff')} stopOpacity='.1' />
            <stop offset='1' stopColor={H('fff')} stopOpacity='0' />
          </radialGradient>
          <linearGradient
            id={`${uid}SVGHjkXJesw`}
            x1='59.255'
            x2='59.255'
            y1='27.067'
            y2='49.852'
            gradientUnits='userSpaceOnUse'
          >
            <stop stopColor={H('bf360c')} stopOpacity='.2' />
            <stop offset='1' stopColor={H('bf360c')} stopOpacity='.02' />
          </linearGradient>
        </defs>
      </g>
    </svg>
  )
}

// ───── assets/motionuikit/svg/syntra-notion-logo.tsx ─────
// The Notion brand mark, from Iconify's open logo sets, in its own colours.
const SyntraNotionLogo = (props: SVGProps< SVGSVGElement>) => {
  return (
    <svg viewBox='0 0 256 268' aria-hidden {...props}>
      <path
        fill={H('fff')}
        d='M16.092 11.538L164.09.608c18.179-1.56 22.85-.508 34.28 7.801l47.243 33.282C253.406 47.414 256 48.975 256 55.207v182.527c0 11.439-4.155 18.205-18.696 19.24L65.44 267.378c-10.913.517-16.11-1.043-21.825-8.327L8.826 213.814C2.586 205.487 0 199.254 0 191.97V29.726c0-9.352 4.155-17.153 16.092-18.188'
      />
      <path d='M164.09.608L16.092 11.538C4.155 12.573 0 20.374 0 29.726v162.245c0 7.284 2.585 13.516 8.826 21.843l34.789 45.237c5.715 7.284 10.912 8.844 21.825 8.327l171.864-10.404c14.532-1.035 18.696-7.801 18.696-19.24V55.207c0-5.911-2.336-7.614-9.21-12.66l-1.185-.856L198.37 8.409C186.94.1 182.27-.952 164.09.608M69.327 52.22c-14.033.945-17.216 1.159-25.186-5.323L23.876 30.778c-2.06-2.086-1.026-4.69 4.163-5.207l142.274-10.395c11.947-1.043 18.17 3.12 22.842 6.758l24.401 17.68c1.043.525 3.638 3.637.517 3.637L71.146 52.095zm-16.36 183.954V81.222c0-6.767 2.077-9.887 8.3-10.413L230.02 60.93c5.724-.517 8.31 3.12 8.31 9.879v153.917c0 6.767-1.044 12.49-10.387 13.008l-161.487 9.361c-9.343.517-13.489-2.594-13.489-10.921M212.377 89.53c1.034 4.681 0 9.362-4.681 9.897l-7.783 1.542v114.404c-6.758 3.637-12.981 5.715-18.18 5.715c-8.308 0-10.386-2.604-16.609-10.396l-50.898-80.079v77.476l16.1 3.646s0 9.362-12.989 9.362l-35.814 2.077c-1.043-2.086 0-7.284 3.63-8.318l9.351-2.595V109.823l-12.98-1.052c-1.044-4.68 1.55-11.439 8.826-11.965l38.426-2.585l52.958 81.113v-71.76l-13.498-1.552c-1.043-5.733 3.111-9.896 8.3-10.404z' />
    </svg>
  )
}

// ───── assets/motionuikit/svg/syntra-openai-logo.tsx ─────
// The OpenAI brand mark, from Iconify's open logo sets, in its own colours.
const SyntraOpenaiLogo = (props: SVGProps< SVGSVGElement>) => {
  return (
    <svg viewBox='0 0 256 260' aria-hidden {...props}>
      <path d='M239.184 106.203a64.72 64.72 0 0 0-5.576-53.103C219.452 28.459 191 15.784 163.213 21.74A65.586 65.586 0 0 0 52.096 45.22a64.72 64.72 0 0 0-43.23 31.36c-14.31 24.602-11.061 55.634 8.033 76.74a64.67 64.67 0 0 0 5.525 53.102c14.174 24.65 42.644 37.324 70.446 31.36a64.72 64.72 0 0 0 48.754 21.744c28.481.025 53.714-18.361 62.414-45.481a64.77 64.77 0 0 0 43.229-31.36c14.137-24.558 10.875-55.423-8.083-76.483m-97.56 136.338a48.4 48.4 0 0 1-31.105-11.255l1.535-.87l51.67-29.825a8.6 8.6 0 0 0 4.247-7.367v-72.85l21.845 12.636c.218.111.37.32.409.563v60.367c-.056 26.818-21.783 48.545-48.601 48.601M37.158 197.93a48.35 48.35 0 0 1-5.781-32.589l1.534.921l51.722 29.826a8.34 8.34 0 0 0 8.441 0l63.181-36.425v25.221a.87.87 0 0 1-.358.665l-52.335 30.184c-23.257 13.398-52.97 5.431-66.404-17.803M23.549 85.38a48.5 48.5 0 0 1 25.58-21.333v61.39a8.29 8.29 0 0 0 4.195 7.316l62.874 36.272l-21.845 12.636a.82.82 0 0 1-.767 0L41.353 151.53c-23.211-13.454-31.171-43.144-17.804-66.405zm179.466 41.695l-63.08-36.63L161.73 77.86a.82.82 0 0 1 .768 0l52.233 30.184a48.6 48.6 0 0 1-7.316 87.635v-61.391a8.54 8.54 0 0 0-4.4-7.213m21.742-32.69l-1.535-.922l-51.619-30.081a8.39 8.39 0 0 0-8.492 0L99.98 99.808V74.587a.72.72 0 0 1 .307-.665l52.233-30.133a48.652 48.652 0 0 1 72.236 50.391zM88.061 139.097l-21.845-12.585a.87.87 0 0 1-.41-.614V65.685a48.652 48.652 0 0 1 79.757-37.346l-1.535.87l-51.67 29.825a8.6 8.6 0 0 0-4.246 7.367zm11.868-25.58L128.067 97.3l28.188 16.218v32.434l-28.086 16.218l-28.188-16.218z' />
    </svg>
  )
}

// ───── assets/motionuikit/svg/syntra-salesforce-logo.tsx ─────
// The Salesforce brand mark, from Iconify's open logo sets, in its own colours.
const SyntraSalesforceLogo = (props: SVGProps< SVGSVGElement>) => {
  return (
    <svg viewBox='0 0 256 180' aria-hidden {...props}>
      <path
        fill={H('00a1e0')}
        d='M106.553 19.651c8.248-8.594 19.731-13.924 32.43-13.924c16.883 0 31.612 9.414 39.455 23.389a54.5 54.5 0 0 1 22.3-4.74c30.449 0 55.134 24.9 55.134 55.615c0 30.719-24.685 55.62-55.134 55.62a54.7 54.7 0 0 1-10.86-1.083c-6.908 12.321-20.07 20.645-35.178 20.645a40.1 40.1 0 0 1-17.632-4.058c-7.002 16.47-23.316 28.019-42.33 28.019c-19.8 0-36.674-12.529-43.152-30.1c-2.83.602-5.763.915-8.772.915c-23.574 0-42.686-19.308-42.686-43.13a43.2 43.2 0 0 1 21.345-37.36a49.4 49.4 0 0 1-4.088-19.727C17.385 22.336 39.626.128 67.06.128c16.106 0 30.42 7.658 39.494 19.523'
      />
      <path
        fill={H('fff')}
        d='M37.17 92.956c-.16.419.058.506.11.58c.48.349.968.6 1.46.88c2.609 1.385 5.071 1.79 7.647 1.79c5.246 0 8.503-2.79 8.503-7.283v-.087c0-4.153-3.676-5.662-7.126-6.75l-.448-.147c-2.601-.845-4.845-1.573-4.845-3.286v-.09c0-1.465 1.311-2.544 3.344-2.544c2.259 0 4.94.75 6.667 1.705c0 0 .506.328.692-.164c.102-.262.976-2.615 1.068-2.87c.098-.277-.077-.481-.255-.59c-1.971-1.2-4.696-2.019-7.516-2.019l-.525.004c-4.801 0-8.153 2.9-8.153 7.057v.087c0 4.383 3.698 5.803 7.162 6.794l.558.172c2.524.775 4.7 1.442 4.7 3.22v.087c0 1.625-1.414 2.835-3.695 2.835c-.885 0-3.708-.018-6.758-1.946c-.368-.215-.583-.371-.867-.542c-.15-.095-.524-.26-.688.236zm76.8 0c-.16.419.058.506.11.58c.48.349.968.6 1.46.88c2.608 1.385 5.071 1.79 7.647 1.79c5.246 0 8.503-2.79 8.503-7.283v-.087c0-4.153-3.676-5.662-7.126-6.75l-.448-.147c-2.601-.845-4.846-1.573-4.846-3.286v-.09c0-1.465 1.312-2.544 3.345-2.544c2.259 0 4.94.75 6.667 1.705c0 0 .506.328.692-.164c.102-.262.976-2.615 1.067-2.87c.099-.277-.076-.481-.255-.59c-1.97-1.2-4.696-2.019-7.515-2.019l-.525.004c-4.802 0-8.153 2.9-8.153 7.057v.087c0 4.383 3.697 5.803 7.162 6.794l.557.172c2.525.775 4.704 1.442 4.704 3.22v.087c0 1.625-1.418 2.835-3.698 2.835a12.4 12.4 0 0 1-6.758-1.946c-.368-.215-.587-.364-.863-.542c-.095-.062-.54-.234-.693.236zm52.429-8.799c0 2.54-.474 4.54-1.406 5.953c-.922 1.4-2.317 2.08-4.263 2.08c-1.949 0-3.337-.677-4.244-2.08c-.918-1.41-1.384-3.413-1.384-5.953c0-2.535.466-4.532 1.384-5.93c.907-1.385 2.295-2.059 4.244-2.059c1.946 0 3.34.674 4.266 2.058c.93 1.4 1.403 3.396 1.403 5.931m4.379-4.707c-.43-1.453-1.1-2.735-1.993-3.803c-.893-1.071-2.022-1.93-3.363-2.557c-1.337-.623-2.918-.94-4.692-.94c-1.778 0-3.359.317-4.696.94c-1.34.626-2.47 1.486-3.366 2.557c-.889 1.071-1.56 2.354-1.993 3.803c-.426 1.447-.64 3.028-.64 4.707c0 1.68.214 3.265.64 4.707c.434 1.45 1.1 2.732 1.997 3.804a9.4 9.4 0 0 0 3.362 2.535c1.34.609 2.918.918 4.696.918c1.774 0 3.352-.31 4.692-.918a9.4 9.4 0 0 0 3.363-2.535c.893-1.068 1.563-2.35 1.993-3.804c.43-1.446.645-3.03.645-4.707c0-1.675-.215-3.26-.645-4.707m35.959 12.063c-.145-.426-.557-.266-.557-.266c-.637.244-1.315.47-2.036.583a16 16 0 0 1-2.401.171c-2.12 0-3.804-.63-5.01-1.876c-1.209-1.246-1.887-3.26-1.88-5.986c.008-2.48.606-4.346 1.68-5.767c1.068-1.413 2.692-2.138 4.86-2.138c1.807 0 3.184.207 4.627.663c0 0 .346.15.51-.303c.382-1.063.667-1.825 1.075-2.994c.116-.332-.168-.474-.27-.514c-.568-.222-1.909-.583-2.922-.736c-.947-.145-2.054-.222-3.286-.222c-1.84 0-3.479.313-4.882.94c-1.399.623-2.586 1.483-3.526 2.554s-1.654 2.353-2.131 3.803c-.474 1.446-.714 3.035-.714 4.714c0 3.632.98 6.569 2.914 8.718c1.938 2.157 4.85 3.253 8.645 3.253c2.244 0 4.547-.455 6.2-1.107c0 0 .318-.153.18-.521zm7.663-9.788c.207-1.41.597-2.583 1.198-3.497c.907-1.388 2.291-2.15 4.237-2.15c1.945 0 3.231.765 4.153 2.15c.612.914.878 2.138.983 3.497zm14.743-3.1c-.372-1.403-1.294-2.82-1.898-3.468c-.955-1.028-1.887-1.746-2.813-2.146a10.8 10.8 0 0 0-4.248-.86c-1.85 0-3.53.31-4.892.95c-1.366.642-2.514 1.516-3.414 2.606c-.9 1.085-1.577 2.379-2.007 3.847c-.434 1.46-.652 3.053-.652 4.732c0 1.709.226 3.3.674 4.732c.451 1.443 1.173 2.715 2.15 3.767c.972 1.06 2.225 1.891 3.726 2.47c1.49.576 3.3.875 5.38.871c4.281-.015 6.537-.969 7.465-1.483c.164-.09.321-.251.124-.71l-.969-2.714c-.145-.405-.557-.255-.557-.255c-1.06.393-2.568 1.1-6.084 1.093c-2.299-.004-4.004-.682-5.071-1.742c-1.097-1.085-1.632-2.68-1.727-4.932l14.824.014s.39-.007.43-.386c.014-.16.51-3.046-.441-6.386m-133.46 3.1c.211-1.41.597-2.583 1.199-3.497c.907-1.388 2.291-2.15 4.236-2.15c1.946 0 3.232.765 4.157 2.15c.609.914.875 2.138.98 3.497zm14.74-3.1c-.372-1.403-1.29-2.82-1.895-3.468c-.954-1.028-1.887-1.746-2.812-2.146a10.8 10.8 0 0 0-4.248-.86c-1.847 0-3.53.31-4.892.95c-1.367.642-2.514 1.516-3.414 2.606c-.9 1.085-1.577 2.379-2.007 3.847c-.43 1.46-.652 3.053-.652 4.732c0 1.709.225 3.3.674 4.732c.451 1.443 1.173 2.715 2.149 3.767c.973 1.06 2.226 1.891 3.727 2.47c1.49.576 3.3.875 5.38.871c4.281-.015 6.536-.969 7.465-1.483c.164-.09.32-.251.124-.71l-.965-2.714c-.15-.405-.561-.255-.561-.255c-1.06.393-2.565 1.1-6.088 1.093c-2.295-.004-4-.682-5.067-1.742c-1.097-1.085-1.633-2.68-1.727-4.932l14.823.014s.39-.007.43-.386c.015-.16.51-3.046-.444-6.386M63.642 91.431c-.58-.462-.66-.579-.856-.878c-.292-.455-.441-1.104-.441-1.927c0-1.304.43-2.24 1.322-2.87c-.01.003 1.275-1.112 4.3-1.072c2.123.03 4.021.343 4.021.343v6.74h.004s-1.884.404-4.004.531c-3.017.182-4.357-.87-4.346-.867m5.898-10.415c-.601-.044-1.38-.07-2.313-.07c-1.272 0-2.5.16-3.65.47a9.5 9.5 0 0 0-3.097 1.436a7.15 7.15 0 0 0-2.15 2.444c-.524.977-.79 2.128-.79 3.417c0 1.312.225 2.452.677 3.385a6.1 6.1 0 0 0 1.935 2.317c.823.601 1.84 1.042 3.02 1.308c1.162.266 2.48.4 3.923.4c1.52 0 3.035-.123 4.503-.375a94 94 0 0 0 3.734-.721c.492-.117 1.039-.266 1.039-.266c.368-.091.338-.485.338-.485l-.007-13.555c0-2.973-.794-5.177-2.357-6.543c-1.555-1.363-3.847-2.051-6.809-2.051c-1.11 0-2.9.153-3.97.368c0 0-3.24.626-4.573 1.668c0 0-.291.182-.131.59l1.05 2.82c.13.364.484.24.484.24s.113-.043.244-.12c2.852-1.552 6.459-1.504 6.459-1.504c1.603 0 2.834.32 3.665.958c.809.619 1.22 1.555 1.22 3.53v.626c-1.275-.182-2.444-.287-2.444-.287m119.56-7.639a.4.4 0 0 0-.223-.532c-.251-.098-1.512-.364-2.485-.426c-1.861-.113-2.896.2-3.821.616c-.918.415-1.938 1.085-2.507 1.847v-1.804c0-.251-.178-.451-.426-.451h-3.8c-.247 0-.426.2-.426.451v22.11c0 .248.204.452.452.452h3.894a.45.45 0 0 0 .448-.452V84.142c0-1.482.164-2.962.492-3.89c.32-.919.758-1.654 1.297-2.183a4.55 4.55 0 0 1 1.833-1.1c.688-.211 1.45-.28 1.989-.28c.776 0 1.628.2 1.628.2c.284.033.445-.142.54-.4c.254-.678.976-2.708 1.114-3.112'
      />
      <path
        fill={H('fff')}
        d='M152.544 63.13a13 13 0 0 0-1.465-.35a11.6 11.6 0 0 0-2.014-.152c-2.682 0-4.795.757-6.277 2.251c-1.476 1.486-2.478 3.749-2.98 6.725l-.183 1.002h-3.366s-.408-.015-.495.43l-.55 3.086c-.04.291.087.477.48.477h3.276l-3.323 18.55c-.258 1.494-.557 2.722-.889 3.654c-.324.918-.64 1.607-1.034 2.11c-.38.48-.736.838-1.356 1.045c-.51.172-1.1.252-1.745.252c-.357 0-.834-.059-1.187-.131a3.5 3.5 0 0 1-.802-.26s-.382-.145-.535.238c-.12.317-.995 2.717-1.1 3.013c-.102.295.043.524.229.593c.437.153.761.255 1.355.397c.824.194 1.52.204 2.172.204c1.362 0 2.608-.193 3.639-.564c1.035-.375 1.938-1.028 2.74-1.91c.863-.954 1.406-1.952 1.923-3.318c.514-1.348.955-3.024 1.304-4.976l3.341-18.897h4.882s.411.014.495-.434l.554-3.082c.036-.295-.087-.477-.485-.477h-4.74c.026-.106.241-1.774.784-3.344c.233-.667.67-1.21 1.038-1.581a3.1 3.1 0 0 1 1.243-.773c.47-.153 1.005-.226 1.592-.226c.444 0 .885.051 1.216.12c.46.099.638.15.758.186c.485.146.55.004.645-.23l1.133-3.11c.117-.335-.171-.478-.273-.518M86.322 95.19c0 .247-.179.448-.426.448h-3.931c-.248 0-.423-.2-.423-.448V63.553c0-.247.175-.448.423-.448h3.93c.248 0 .427.2.427.448z'
      />
    </svg>
  )
}

// ───── assets/motionuikit/svg/syntra-slack-logo.tsx ─────
// The Slack brand mark, from Iconify's open logo sets, in its own colours.
const SyntraSlackLogo = (props: SVGProps< SVGSVGElement>) => {
  return (
    <svg viewBox='0 0 256 256' aria-hidden {...props}>
      <path
        fill={H('e01e5a')}
        d='M53.841 161.32c0 14.832-11.987 26.82-26.819 26.82S.203 176.152.203 161.32c0-14.831 11.987-26.818 26.82-26.818H53.84zm13.41 0c0-14.831 11.987-26.818 26.819-26.818s26.819 11.987 26.819 26.819v67.047c0 14.832-11.987 26.82-26.82 26.82c-14.83 0-26.818-11.988-26.818-26.82z'
      />
      <path
        fill={H('36c5f0')}
        d='M94.07 53.638c-14.832 0-26.82-11.987-26.82-26.819S79.239 0 94.07 0s26.819 11.987 26.819 26.819v26.82zm0 13.613c14.832 0 26.819 11.987 26.819 26.819s-11.987 26.819-26.82 26.819H26.82C11.987 120.889 0 108.902 0 94.069c0-14.83 11.987-26.818 26.819-26.818z'
      />
      <path
        fill={H('2eb67d')}
        d='M201.55 94.07c0-14.832 11.987-26.82 26.818-26.82s26.82 11.988 26.82 26.82s-11.988 26.819-26.82 26.819H201.55zm-13.41 0c0 14.832-11.988 26.819-26.82 26.819c-14.831 0-26.818-11.987-26.818-26.82V26.82C134.502 11.987 146.489 0 161.32 0s26.819 11.987 26.819 26.819z'
      />
      <path
        fill={H('ecb22e')}
        d='M161.32 201.55c14.832 0 26.82 11.987 26.82 26.818s-11.988 26.82-26.82 26.82c-14.831 0-26.818-11.988-26.818-26.82V201.55zm0-13.41c-14.831 0-26.818-11.988-26.818-26.82c0-14.831 11.987-26.818 26.819-26.818h67.25c14.832 0 26.82 11.987 26.82 26.819s-11.988 26.819-26.82 26.819z'
      />
    </svg>
  )
}

// ───── assets/motionuikit/svg/syntra-zapier-logo.tsx ─────
// The Zapier brand mark, from Iconify's open logo sets, in its own colours.
const SyntraZapierLogo = (props: SVGProps< SVGSVGElement>) => {
  return (
    <svg viewBox='0 0 256 256' aria-hidden {...props}>
      <path
        fill={H('FF4A00')}
        d='M128.08 0c7.231.013 14.343.624 21.256 1.78V76.3l52.831-52.696a128.425 128.425 0 0 1 16.34 13.789a128.468 128.468 0 0 1 13.84 16.312L179.513 106.4h74.715A127.58 127.58 0 0 1 256 127.587v.173c0 7.226-.613 14.306-1.772 21.2H179.5l52.847 52.682a129.615 129.615 0 0 1-13.824 16.312h-.015a128.254 128.254 0 0 1-16.326 13.789l-52.846-52.696v74.52a130.321 130.321 0 0 1-21.243 1.781h-.186a130.26 130.26 0 0 1-21.23-1.78v-74.52l-52.831 52.695a128.401 128.401 0 0 1-30.18-30.1L76.5 148.96H1.785A126.984 126.984 0 0 1 0 127.72v-.371c.012-1.875.135-4.166.311-6.536l.055-.713c.522-6.671 1.419-13.7 1.419-13.7H76.5L23.666 53.705a126.265 126.265 0 0 1 13.81-16.286l.026-.026a127.746 127.746 0 0 1 16.344-13.789L106.677 76.3V1.78A130.278 130.278 0 0 1 127.933 0h.147Zm-.013 95.76h-.122a76.718 76.718 0 0 0-27.034 4.902a76.662 76.662 0 0 0-4.915 26.952v.12a76.383 76.383 0 0 0 4.927 26.951a76.608 76.608 0 0 0 27.022 4.902h.122c9.51 0 18.617-1.74 27.022-4.902a76.146 76.146 0 0 0 4.915-26.952v-.12c0-9.484-1.747-18.57-4.915-26.951a76.614 76.614 0 0 0-27.022-4.902Z'
      />
    </svg>
  )
}

// ───── components/motionuikit/sections/syntra/stage-card-body.tsx ─────
// Third-party Imports

type AppName = Stage['apps'][number]

const APP_LOGOS: Record< AppName, (props: SVGProps< SVGSVGElement>) => ReactElement> = {
  Slack: SyntraSlackLogo,
  GitHub: SyntraGithubLogo,
  Salesforce: SyntraSalesforceLogo,
  'Google Slides': SyntraGoogleSlidesLogo,
  Notion: SyntraNotionLogo,
  'Google Drive': SyntraGoogleDriveLogo,
  Zapier: SyntraZapierLogo,
  Airtable: SyntraAirtableLogo,
  OpenAI: SyntraOpenaiLogo,
  Gemini: SyntraGeminiLogo,
  Claude: SyntraClaudeLogo,
  'Google Sheets': SyntraGoogleSheetsLogo,
  'Google Analytics': SyntraGoogleAnalyticsLogo
}

// The small orange mark under each stage number.
const STAGE_ICONS = {
  sources: Cable,
  context: FilePen,
  automation: Workflow,
  outcomes: ChartNoAxesColumnIncreasing
}

type AppLogosProps = { stage: Stage; tiles: boolean; className?: string }

// The apps behind a stage, as their own brand marks. The AI layer sets them in small tiles.
const AppLogos = ({ stage, tiles, className }: AppLogosProps) => {
  return (
    <div className={cn('n3-stage-apps flex shrink-0 items-center', tiles ? 'gap-[0.45em]' : 'gap-[0.3em]', className)}>
      {stage.apps.map(app => {
        const Logo = APP_LOGOS[app]

        return tiles ? (
          <span
            key={app}
            className='border-[var(--n3-line)] grid size-[2.15em] place-items-center rounded-[0.6em] border bg-white shadow-[0_1px_2px_var(--n3-card-shadow)]'
          >
            <Logo className='size-[1.2em]' />
          </span>
        ) : (
          <Logo key={app} className='size-[1.45em]' />
        )
      })}
      <span className='sr-only'>Works with {stage.apps.join(', ')}</span>
    </div>
  )
}

/**
 * A two-row grid: number and stage icon each centred in a box one line tall, so they line up
 * with their text, and the apps on the right. The AI layer swaps its icon for a "Express core" tag
 * and puts its apps on their own row. Sized in em, so the card scales with its font size.
 */
const StageCardBody = ({ index }: { index: number }) => {
  // Vars
  const stage = STAGES[index]
  const main = isMain(stage)
  const Icon = 'icon' in stage ? STAGE_ICONS[stage.icon] : null

  return (
    <div className='grid grid-cols-[1.35em_minmax(0,1fr)_auto] items-start gap-x-[0.5em]'>
      {/* One title line tall (1.05em type at 1.25 leading), in this span's own 0.8em units. */}
      <span className='text-[var(--n3-grey)] col-start-1 row-start-1 flex h-[1.64em] items-center text-[0.8em] font-medium tabular-nums'>
        {String(index + 1).padStart(2, '0')}
      </span>
      <div
        className={cn(
          'col-start-2 row-start-1 flex flex-wrap items-baseline justify-between gap-x-[0.6em]',
          main && 'col-span-2'
        )}
      >
        <p className='text-[var(--n3-ink)] text-[1.05em] leading-[1.25] font-semibold tracking-[-0.015em] md:whitespace-nowrap'>
          {stage.title}
        </p>
        {main && (
          <span className='text-[var(--n3-accent)] shrink-0 text-[0.72em] font-semibold tracking-[0.12em] uppercase'>
            Express core
          </span>
        )}
      </div>

      {/* Outputs' first line: 0.88em type at 1.45 leading. */}
      {Icon && (
        <span className='col-start-1 row-start-2 mt-[0.4em] flex h-[1.276em] items-center'>
          <Icon className='text-[var(--n3-accent)] size-[1.25em]' strokeWidth={1.7} aria-hidden />
        </span>
      )}
      <div className={cn('col-start-2 row-start-2 mt-[0.4em]', main && 'col-span-2')}>
        <p className='text-[var(--n3-grey)] text-[0.88em] leading-[1.45]'>{stage.items.join(' · ')}</p>
        
      </div>

      
    </div>
  )
}

// ───── components/motionuikit/sections/syntra/stage-cards.tsx ─────
// Third-party Imports

const pct = (n: number) => `${(n / ARTBOARD) * 100}%`

// Cards ease away from the core: left cards to the left, right cards to the right.
const cardDirection = (index: number) => (STAGES[index].side === 'left' ? -1 : 1)

type StageCardProps = {
  index: number
  progress: MotionValue< number>
  open: boolean
  lit: boolean
  onHover: (index: number | null) => void
}

// One stage card beside its plate. Hidden at rest; it fades in and eases outward as its plate settles.
const StageCard = ({ index, progress, open, lit, onHover }: StageCardProps) => {
  // Hooks: sequenced to appear progressively as its plate unboxes and reaches height
  const opacity = useTransform(progress, (p: number) => {
    const prog = plateProgress(index, p)
    return clamp((prog - 0.5) / 0.4)
  })

  const x = useTransform(progress, (p: number) => {
    const prog = plateProgress(index, p)
    const cardFactor = clamp((prog - 0.4) / 0.6)
    return (
      -cardDirection(index) * STAGE_CARDS.restInset * (1 - cardFactor) +
      cardDirection(index) * STAGE_CARDS.outward * cardFactor
    )
  })

  // Vars
  const stage = STAGES[index]
  const { edge, width } = STAGE_CARDS[stage.side]
  const left = stage.side === 'left' ? edge : 100 - edge - width

  const handlePointerEnter = (e: ReactPointerEvent) => {
    if (e.pointerType === 'mouse') onHover(index)
  }

  const handlePointerLeave = () => {
    onHover(null)
  }

  return (
    <li
      style={{ top: pct(cornerY(index)), left: `${left}%`, width: `${width}%` }}
      className='absolute -translate-y-1/2'
    >
      <m.div
        style={{ opacity, x }}
        onPointerEnter={handlePointerEnter}
        onPointerLeave={handlePointerLeave}
        className={cn(
          'n3-stage-card px-[0.8em] py-[0.65em]',
          open && 'pointer-events-auto',
          lit && 'n3-stage-card-lit'
        )}
      >
        <StageCardBody index={index} />
      </m.div>
    </li>
  )
}

type StageCardsProps = {
  progress: MotionValue< number>
  open: boolean
  hovered: number | null
  onHover: (index: number | null) => void
}

/**
 * The five stages as floating cards beside their plates, from tablet up. Font size follows the
 * visual's width (cqw), so the cards keep their place against the plates at every size.
 */
const StageCards = ({ progress, open, hovered, onHover }: StageCardsProps) => {
  return (
    <ul
      aria-label='How Zentro parcel delivery works'
      aria-hidden={!open}
      className='pointer-events-none absolute inset-0 m-0 hidden list-none p-0 text-[clamp(9.5px,1.35cqw,12.5px)] md:block'
    >
      {STAGES.map((stage, i) => (
        <StageCard key={stage.title} index={i} progress={progress} open={open} lit={hovered === i} onHover={onHover} />
      ))}
    </ul>
  )
}

// ───── assets/motionuikit/svg/syntra-stage-connector.tsx ─────
// Third-party Imports

const ORANGE4 = H('FF7A2F')

/**
 * plate: x of the plate's corner tip; card: x of the card's inner edge; y: the line's height.
 * All in artboard units.
 */
type SyntraStageConnectorProps = {
  className?: string
  plate: number
  card: number
  y: number
  side: 'left' | 'right'
  index: number
  main: boolean
  lit: boolean
  active: boolean
  progress: MotionValue< number>
}

// Where the line meets the plate's corner and the card's node, in artboard units.
const lineEnds = (plate: number, card: number, side: 'left' | 'right') => {
  const dir = side === 'right' ? 1 : -1

  return { plateX: plate + dir * 7, nodeX: card - dir * 10 }
}

// Card → orange node → hairline → the corner of its plate. A bead runs the line while the stack is open.
const SyntraStageConnector = ({
  plate,
  card,
  y,
  side,
  index,
  main,
  lit,
  active,
  progress,
  className
}: SyntraStageConnectorProps) => {
  // Hooks: line draws progressively as its corresponding plate emerges and settles
  const show = useTransform(progress, (p: number) => {
    const prog = plateProgress(index, p)
    return clamp((prog - 0.45) / 0.4)
  })
  const t = useMotionValue(0)

  const beadX = useTransform(t, k => {
    const ends = lineEnds(plate, card, side)

    return ends.nodeX + (ends.plateX - ends.nodeX) * k
  })

  const beadOpacity = useTransform([t, show], ([k, s]: number[]) => s * Math.sin(Math.PI * k))

  // Vars
  const { plateX, nodeX } = lineEnds(plate, card, side)

  useEffect(() => {
    if (!active) return

    const controls = animate(t, [0, 1], {
      duration: STAGE_CARDS.beadSeconds,
      ease: 'easeInOut',
      repeat: Infinity,
      repeatDelay: 1.4,
      delay: 0.5 + index * 0.25
    })

    return () => {
      controls.stop()
      t.set(0)
    }
  }, [active, index, t])

  return (
    <m.svg aria-hidden viewBox={`0 0 ${ARTBOARD} ${ARTBOARD}`} style={{ opacity: show }} className={className}>
      <line x1={plateX} x2={nodeX} y1={y} y2={y} stroke={H('CFCAC1')} strokeWidth='1' />
      <m.line
        x1={plateX}
        x2={nodeX}
        y1={y}
        y2={y}
        stroke={ORANGE4}
        strokeWidth='1.2'
        initial={false}
        animate={{ opacity: lit ? 0.9 : 0 }}
        transition={{ duration: 0.25 }}
      />
      <circle cx={plateX} cy={y} r='2.6' fill={H('A8A399')} />
      <m.circle
        cx={nodeX}
        cy={y}
        r={main ? 10 : 8}
        fill={ORANGE4}
        initial={false}
        animate={{ opacity: lit || main ? 0.22 : 0.12 }}
        transition={{ duration: 0.25 }}
      />
      <circle cx={nodeX} cy={y} r='3.6' fill={ORANGE4} />
      <m.circle cx={beadX} cy={y} r='2.6' fill={ORANGE4} style={{ opacity: beadOpacity }} />
    </m.svg>
  )
}

// ───── components/motionuikit/sections/syntra/stage-connectors.tsx ─────
// Third-party Imports

type StageConnectorsProps = {
  progress: MotionValue< number>
  active: boolean
  hovered: number | null
}

// Hairlines from each card to its plate, from tablet up. They tilt and float with the stack.
const StageConnectors = ({ progress, active, hovered }: StageConnectorsProps) => {
  return (
    <div className='pointer-events-none absolute inset-0 hidden md:block'>
      {STAGES.map((stage, i) => {
        const { edge, width } = STAGE_CARDS[stage.side]
        const cardEdge = stage.side === 'left' ? (edge + width) * 10 : (100 - edge - width) * 10

        return (
          <SyntraStageConnector
            key={stage.title}
            plate={tipX(i, stage.side)}
            card={cardEdge}
            y={cornerY(i)}
            side={stage.side}
            index={i}
            main={isMain(stage)}
            lit={hovered === i}
            active={active}
            progress={progress}
            className='pointer-events-none absolute inset-0 size-full overflow-visible'
          />
        )
      })}
    </div>
  )
}

// ───── components/motionuikit/sections/syntra/stage-list.tsx ─────
// Third-party Imports

// On a phone the visual is too narrow for cards beside it, so they sit below it as a grid.
const StageList = ({ reduce }: { reduce: boolean }) => {
  return (
    <m.ul
      initial={reduce ? false : { opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.9, delay: 0.5, ease: EASE }}
      aria-label='How Zentro parcel delivery works'
      className='m-0 grid list-none grid-cols-1 gap-2.5 p-0 text-[13px] sm:grid-cols-2 md:hidden'
    >
      {STAGES.map((stage, i) => (
        <li key={stage.title} className='n3-stage-card px-[1.05em] py-[0.95em]'>
          <StageCardBody index={i} />
        </li>
      ))}
    </m.ul>
  )
}

// ───── components/motionuikit/sections/syntra/core-visual.tsx ─────
// Third-party Imports

// Follows the tab being shown or hidden, for useSyncExternalStore
const subscribeToVisibility = (onChange: () => void) => {
  document.addEventListener('visibilitychange', onChange)

  return () => document.removeEventListener('visibilitychange', onChange)
}

const isPageVisible = () => !document.hidden

// The server has no tab to hide, so it renders as shown
const isPageVisibleOnServer = () => true

type ParallaxLayerProps = {
  x: MotionValue< number>
  y: MotionValue< number>
  reach: number
  children: ReactNode
}

// Each layer slides against the pointer by a different amount, which is what reads as depth.
const ParallaxLayer = ({ x, y, reach, children }: ParallaxLayerProps) => {
  // Hooks
  const tx = useTransform(x, v => v * reach)
  const ty = useTransform(y, v => v * reach)

  return (
    <m.div style={{ x: tx, y: ty }} className='pointer-events-none absolute inset-0'>
      {children}
    </m.div>
  )
}

type TiltedProps = {
  rotateX: MotionValue< number>
  rotateY: MotionValue< number>
  children: ReactNode
}

// The tilt toward the cursor and the slow float, shared by every layer that moves with the stack.
const Tilted = ({ rotateX, rotateY, children }: TiltedProps) => {
  return (
    <div className='pointer-events-none absolute inset-0 [perspective:1400px]'>
      <m.div style={{ rotateX, rotateY }} className='absolute inset-0'>
        <div className='n3-float absolute inset-0'>{children}</div>
      </m.div>
    </div>
  )
}

const CoreVisual = () => {
  // Hooks
  const reduce = useReducedMotion() ?? false

  // States
  const [open, setOpen] = useState(false)
  const [hovered, setHovered] = useState< number | null>(null)
  const [, setCinematicPhase] = useState<'waiting' | 'arrival' | 'unboxing' | 'emerging' | 'cards' | 'settled'>(
    reduce ? 'settled' : 'waiting'
  )

  // Refs
  const pointerType = useRef('mouse')
  const visualRef = useRef< HTMLDivElement>(null)
  const animStarted = useRef(false)

  // The margin covers the outer orbit and its beads running a little past the visual's box
  const [onScreen, setOnScreen] = useState(true)
  useEffect(() => {
    const node = visualRef.current
    if (!node || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver(([entry]) => setOnScreen(entry.isIntersecting), { rootMargin: '40px' })
    observer.observe(node)
    return () => observer.disconnect()
  }, [])
  const pageVisible = useSyncExternalStore(subscribeToVisibility, isPageVisible, isPageVisibleOnServer)

  const px = useMotionValue(0)
  const py = useMotionValue(0)
  const x = useSpring(px, { stiffness: 55, damping: 18, mass: 0.9 })
  const y = useSpring(py, { stiffness: 55, damping: 18, mass: 0.9 })
  const rotateY = useTransform(x, v => v * MOTION.tiltDegrees)

  // 0 = stacked, 1 = apart. One spring drives the plates, connectors, cards and shadow together.
  const target = useMotionValue(0)
  const progress = useSpring(target, reduce ? { duration: 0.01 } : EXPLODE.spring)

  // 3D Delivery Parcel Box Motion Values
  const boxY = useMotionValue(reduce ? 0 : -140)
  const boxScale = useMotionValue(reduce ? 1 : 0.88)
  const flapProgress = useMotionValue(reduce ? 1 : 0)
  const boxFade = useTransform(target, [0, 0.5], [1, 0.92])

  // While it is open the stack also leans back a little more, which is where the extra depth comes from.
  const rotateX = useTransform([y, progress], ([v, p]: number[]) => v * -MOTION.tiltDegrees + p * 4)
  const explodedOpacity = useTransform(progress, [0, 0.03], [0, 1])
  const shadowOpacity = useTransform(progress, [0, 1], [1, 0.55])

  // The orbits step back while the stack is open, so the plates and cards have the stage.
  const orbitOpacity = useTransform(progress, [0, 0.6], [1, MOTION.orbitOpenOpacity])

  // The hint under the core bows out as soon as the stack starts to open.
  const hintOpacity = useTransform(progress, [0, 0.25], [1, 0])

  // The beads hold still while the stack is open, off screen or in a hidden tab; both halves share it, so they stay in step
  const beadsPaused = open || !onScreen || !pageVisible

  // Reactive unboxing on open / hover state
  useEffect(() => {
    target.set(open ? 1 : 0)

    if (reduce) {
      flapProgress.set(open ? 1 : 0)
      return
    }

    if (open) {
      // Opening: flaps unfold in 3D perspective, radiant glow blooms, plates emerge
      setCinematicPhase('emerging')
      animate(flapProgress, 1, {
        duration: 0.8,
        ease: [0.16, 1, 0.3, 1]
      })
    } else {
      // Closing: plates glide back into the box cavity first, then flaps fold closed
      setCinematicPhase('arrival')
      const timer = setTimeout(() => {
        animate(flapProgress, 0, {
          duration: 0.65,
          ease: [0.22, 1, 0.36, 1]
        })
      }, 260)
      return () => clearTimeout(timer)
    }
  }, [open, target, reduce, flapProgress])

  // Viewport-triggered Parcel Arrival Drop
  useEffect(() => {
    if (!onScreen || !pageVisible || animStarted.current) return
    animStarted.current = true

    if (reduce) {
      setCinematicPhase('settled')
      boxY.set(0)
      boxScale.set(1)
      flapProgress.set(1)
      setOpen(true)
      target.set(1)
      return
    }

    const runArrival = async () => {
      // Scene 1: Parcel Arrival (0.0-0.8s) - drops into place with realistic dampening bounce
      setCinematicPhase('arrival')
      await animate(boxY, [-140, 0, -18, 0, -4, 0], {
        duration: 0.85,
        times: [0, 0.58, 0.74, 0.86, 0.94, 1],
        ease: 'easeOut'
      })
      await animate(boxScale, [0.88, 1, 1.02, 1], {
        duration: 0.35,
        ease: 'easeOut'
      })
    }

    runArrival()
  }, [onScreen, pageVisible, reduce, boxY, boxScale, flapProgress, target])

  const handlePointerEnter = (e: ReactPointerEvent<HTMLDivElement>) => {
    if (reduce || e.pointerType !== 'mouse') return
    setOpen(true)
  }

  const handlePointerMove = (e: ReactPointerEvent<HTMLDivElement>) => {
    if (reduce || e.pointerType !== 'mouse') return

    const box = e.currentTarget.getBoundingClientRect()

    px.set(((e.clientX - box.left) / box.width - 0.5) * 2)
    py.set(((e.clientY - box.top) / box.height - 0.5) * 2)
  }

  const handlePointerLeave = () => {
    px.set(0)
    py.set(0)
    if (!reduce) setOpen(false)
  }

  // Hovering a card lights its connector
  const handleCardHover = (index: number | null) => {
    setHovered(index)
  }

  const handleCorePointerDown = (e: ReactPointerEvent<HTMLButtonElement>) => {
    pointerType.current = e.pointerType
  }

  const handleCorePointerEnter = (e: ReactPointerEvent<HTMLButtonElement>) => {
    if (e.pointerType === 'mouse' && !reduce) setOpen(true)
  }

  // A mouse opens it by hovering the core. Touch and keyboard have no hover, so they toggle instead.
  const handleCoreClick = (e: MouseEvent<HTMLButtonElement>) => {
    if (e.detail === 0 || pointerType.current !== 'mouse') setOpen(v => !v)
  }

  const handleCoreBlur = () => {
    if (!reduce) setOpen(false)
  }

  return (
    <LazyMotion features={domAnimation}>
      <>
        <style>{BLOCK_CSS}</style>
        {/* Fades and scales in with CSS (n3-enter), from the first paint rather than after hydration. */}
        <div
          ref={visualRef}
          onPointerEnter={handlePointerEnter}
          onPointerMove={handlePointerMove}
          onPointerLeave={handlePointerLeave}
          className='n3-enter @container relative mt-0 -mb-[13%] -ml-[12.5%] aspect-square w-[125%] max-w-none md:mx-auto md:mt-0 md:-mb-[12%] md:w-full md:max-w-[720px] lg:mx-0 lg:mt-0 lg:-mb-[13%] lg:w-[104%] lg:max-w-[740px]'
        >
          {/* Floating top-right Express Transit status badge — fades out when stage cards open */}
          <m.div
            style={{ opacity: hintOpacity }}
            className='pointer-events-none absolute top-[6%] right-[2%] z-20 flex items-center gap-2.5 rounded-full border border-[var(--n3-line-soft)] bg-[var(--white)] px-4 py-2 shadow-[0_12px_28px_-10px_var(--n3-card-shadow)]'
          >
            <span className='n3-dot inline-block h-2 w-2 shrink-0 rounded-full bg-[var(--n3-accent)]' />
            <div className='flex flex-col text-left leading-tight'>
              <span className='text-[11.5px] font-bold text-[var(--n3-ink)]'>In Express Transit</span>
              <span className='text-[9.5px] font-medium text-[var(--n3-grey)]'>Colombo → Islandwide</span>
            </div>
          </m.div>

          <div className='absolute inset-0'>
            {/* 2:1 Isometric diamond floor grid underneath the core */}
            <svg
              viewBox='0 0 1000 1000'
              aria-hidden
              className='n3-floor pointer-events-none absolute -inset-[12%] h-[124%] w-[124%]'
            >
              <defs>
                <pattern id='n3-iso-grid' width='69.28' height='40' patternUnits='userSpaceOnUse'>
                  <path
                    d='M 0 20 L 34.64 0 L 69.28 20 L 34.64 40 Z'
                    fill='none'
                    stroke='var(--n3-line)'
                    strokeWidth='1'
                  />
                </pattern>
              </defs>
              <rect width='1000' height='1000' fill='url(#n3-iso-grid)' />
            </svg>

            <div aria-hidden className='n3-halo pointer-events-none absolute inset-[6%]' />

            <div className='n3-fade-edge absolute inset-0'>
              <ParallaxLayer x={x} y={y} reach={-MOTION.backdropParallax}>
                <SyntraDepthBackdrop className='absolute inset-0 size-full overflow-visible' />
                <SyntraTechnicalBackdrop className='absolute inset-0 size-full overflow-visible' />
              </ParallaxLayer>
            </div>

            {/**
             * The orbits sit outside the faded layer: the outer ring runs past the visual's box, and a
             * mask would cut it off there.
             */}
            <ParallaxLayer x={x} y={y} reach={MOTION.ringParallax}>
              <m.div style={{ opacity: orbitOpacity }} className='absolute inset-0'>
                <SyntraOrbitalSystem className='absolute inset-0 size-full overflow-visible' />
                <SyntraParticles
                  half='back'
                  animate={!reduce}
                  paused={beadsPaused}
                  className='absolute inset-0 size-full overflow-visible'
                />
              </m.div>
            </ParallaxLayer>

            <m.div style={{ opacity: shadowOpacity }} className='absolute inset-0'>
              <SyntraCoreShadow className='pointer-events-none absolute inset-0 size-full overflow-visible' />
            </m.div>

            <Tilted rotateX={rotateX} rotateY={rotateY}>
              {/* Depth Layer 1: Parcel back cavity, back flaps, and volumetric orange glow */}
              <ZentroParcelBack
                boxY={boxY}
                boxScale={boxScale}
                flapProgress={flapProgress}
                opacity={boxFade}
                className='pointer-events-none absolute inset-0 size-full overflow-visible'
              />

              {/* Depth Layer 2: Five Floating Logistics Layers that emerge sequentially from inside the box */}
              <m.div style={{ opacity: explodedOpacity }} className='absolute inset-0'>
                <SyntraExplodedCore progress={progress} className='absolute inset-0 size-full overflow-visible' />
              </m.div>

              {/* Depth Layer 3: Parcel front cardboard walls (kraft texture, shipping label, brand mark) & front flaps */}
              <ZentroParcelFront
                boxY={boxY}
                boxScale={boxScale}
                flapProgress={flapProgress}
                opacity={boxFade}
                className='pointer-events-none absolute inset-0 size-full overflow-visible'
              />

              {/* Depth Layer 4: Progressive Stage Connectors */}
              <StageConnectors progress={progress} active={open && !reduce} hovered={hovered} />
            </Tilted>

            <ParallaxLayer x={x} y={y} reach={MOTION.ringParallax}>
              <m.div style={{ opacity: orbitOpacity }} className='absolute inset-0'>
                <SyntraParticles
                  half='front'
                  animate={!reduce}
                  paused={beadsPaused}
                  className='absolute inset-0 size-full overflow-visible'
                />
              </m.div>
            </ParallaxLayer>

            {/* The cards ride the same tilt and float in a layer of their own, above the front orbit. */}
            <Tilted rotateX={rotateX} rotateY={rotateY}>
              <StageCards progress={progress} open={open} hovered={hovered} onHover={handleCardHover} />
            </Tilted>
          </div>

          {/* A quiet invitation under the core. Touch screens have no hover, so they are told to tap. */}
          <m.p
            aria-hidden
            style={{ opacity: hintOpacity }}
            className='text-[var(--n3-grey)] pointer-events-none absolute top-[79%] left-1/2 m-0 flex -translate-x-1/2 items-center gap-1.5 text-[12.5px] whitespace-nowrap'
          >
            <MousePointer2 className='text-[var(--n3-accent)] h-3.5 w-3.5' strokeWidth={1.8} />
            <span className='[@media(hover:none)]:hidden'>Hover to follow a parcel&apos;s journey</span>
            <span className='hidden [@media(hover:none)]:inline'>Tap to follow a parcel&apos;s journey</span>
          </m.p>

          <button
            type='button'
            aria-expanded={open}
            aria-label='Follow a parcel journey through Zentro'
            onPointerDown={handleCorePointerDown}
            onPointerEnter={handleCorePointerEnter}
            onClick={handleCoreClick}
            onBlur={handleCoreBlur}
            className='focus-visible:outline-[var(--n3-accent)] absolute top-[30%] left-[22%] h-[44%] w-[56%] cursor-pointer rounded-[3rem] focus-visible:outline-2 focus-visible:outline-offset-4'
          />
        </div>
        <StageList reduce={reduce} />
      </>
    </LazyMotion>
  )
}

// ───── components/motionuikit/sections/syntra/flip-label.tsx ─────
// Third-party Imports

/**
 * A button label drawn as two identical faces of a small cube. Put `n3-flip` on the button and
 * hovering it turns the cube over; the twin face is hidden from screen readers.
 */

export function FlipLabel({ children, className = '' }: { children: React.ReactNode; className?: string }) {
  return (
    <span className={cn('relative inline-block overflow-hidden', className)}>
      <span className="inline-block transition-transform duration-300 group-hover:-translate-y-full">
        {children}
      </span>
      <span className="absolute left-0 top-full inline-block transition-transform duration-300 group-hover:-translate-y-full">
        {children}
      </span>
    </span>
  );
}

export { CoreVisual, CoreVisual as SyntraHero };
export default CoreVisual;
