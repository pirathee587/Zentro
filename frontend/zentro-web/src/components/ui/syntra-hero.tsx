'use client'

import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import type { MouseEvent, PointerEvent as ReactPointerEvent, ReactNode, SVGProps } from 'react'
import { LazyMotion, animate, domAnimation, m, useMotionValue, useReducedMotion, useSpring, useTransform } from 'motion/react'
import type { MotionValue } from 'motion/react'
import { Cable, ChartNoAxesColumnIncreasing, FilePen, MousePointer2, Workflow } from 'lucide-react'
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

// ───── components/motionuikit/sections/syntra/stage-card-body.tsx ─────
// Third-party Imports

// The small orange mark under each stage number.
const STAGE_ICONS = {
  sources: Cable,
  context: FilePen,
  automation: Workflow,
  outcomes: ChartNoAxesColumnIncreasing
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
