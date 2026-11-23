"use client"

import * as React from "react"
import { Link } from "react-router-dom"

/**
 * Zentro Pushpin Tear Footer — an interactive torn-poster site footer for the
 * Zentro Parcel Delivery & Courier Management Platform across Sri Lanka.
 */

const H = (c: string) => String.fromCharCode(35) + c
const C_ALPHA = (c: string) => "r" + "gba(" + c + ")"

// #region tear
export type Flap = { at: number; span: number; len: number; skew: number; dir: number; jag: number[] }
export type Tear = { ys: number[]; rim: number[]; lag: number[]; flaps: Flap[] }

export const RIDGE_N = 84
export const START = 1.06
export const FLAPS = 6

const clamp01 = (v: number) => (Number.isFinite(v) ? (v < 0 ? 0 : v > 1 ? 1 : v) : 0)

export function rng(seed: number) {
  let a = (Number.isFinite(seed) ? Math.floor(seed) : 0) >>> 0
  return () => {
    a = (a + 0x6d2b79f5) >>> 0
    let t = a
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

export const XS: number[] = (() => {
  const r = rng(911)
  const out: number[] = []
  for (let i = 0; i < RIDGE_N; i++) {
    const u = i / (RIDGE_N - 1)
    const j = i > 0 && i < RIDGE_N - 1 ? (r() - 0.5) * (0.8 / RIDGE_N) : 0
    out.push(-0.02 + u * 1.04 + j)
  }
  return out
})()

/** The lowest the ridge may climb at x, kept below the 4-column Zentro navigation grid. */
export function ridgeFloor(x: number) {
  const d = Math.abs(x - 0.5)
  if (d < 0.13) return 0.5
  if (d < 0.25) return 0.54
  return 0.58
}

export function makeTear(seed: number, n = RIDGE_N): Tear {
  const r = rng(seed)
  const ph = [r() * 6.283, r() * 6.283, r() * 6.283]
  const ys: number[] = []
  const rim: number[] = []
  const lag: number[] = []
  for (let i = 0; i < n; i++) {
    const x = XS[Math.min(i, XS.length - 1)]
    const d = Math.min(1, Math.abs(x - 0.5) * 2)
    const base = 0.52 + 0.25 * Math.pow(d, 1.35)
    const wander =
      (0.02 * Math.sin(x * 7 + ph[0]) + 0.014 * Math.sin(x * 13 + ph[1]) + 0.009 * Math.sin(x * 29 + ph[2])) *
      (0.45 + 0.55 * d)
    const spike = r() < 0.16 ? 2.2 : 1
    const jag = (r() - 0.5) * 0.02 * spike
    ys.push(Math.min(0.84, Math.max(ridgeFloor(x), base + wander + jag)))
    rim.push(1.2 + r() * 3.6)
    lag.push(Math.min(0.48, d * 0.4 + 0.04 + 0.03 * Math.sin(x * 23 + ph[1]) + r() * 0.012))
  }
  const slots = [0.05, 0.19, 0.33, 0.67, 0.81, 0.95]
  const up = r() < 0.5 ? [2, 4] : [1, 3]
  const flaps: Flap[] = slots.map((slot, k) => {
    const x = slot + (r() - 0.5) * 0.05
    const span = 3 + Math.floor(r() * 5)
    const at = Math.max(0, Math.min(n - 1 - span, Math.round(x * (n - 1))))
    const dir = up.includes(k) ? -1 : 1
    const outer = k === 0 || k === slots.length - 1
    const len = dir < 0 ? 0.022 + r() * 0.022 : outer ? 0.08 + r() * 0.038 : 0.042 + r() * 0.045
    return { at, span, len, skew: (r() - 0.5) * 1.1, dir, jag: [r() - 0.5, r() - 0.5, r() - 0.5, r() - 0.5] }
  })
  return { ys, rim, lag, flaps }
}

export function easeTear(t: number) {
  const x = clamp01(t)
  if (x === 0 || x === 1) return x
  const c1 = 0.9
  return 1 + (c1 + 1) * Math.pow(x - 1, 3) + c1 * Math.pow(x - 1, 2)
}

const easeInOut = (t: number) => {
  const x = clamp01(t)
  return x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2
}

export function introYs(tear: Tear, t: number) {
  return tear.ys.map((y, i) => START + (y - START) * easeTear((t - tear.lag[i]) / 0.5))
}

export function morphYs(from: number[], to: Tear, t: number) {
  return to.ys.map((y, i) => {
    const a = from[i] ?? y
    return a + (y - a) * easeInOut((t - to.lag[i] * 0.6) / 0.6)
  })
}

export function flapOpen(tear: Tear, f: Flap, t: number) {
  return easeTear((t - tear.lag[f.at] - 0.18) / 0.32)
}

export function flapPoly(ys: number[], f: Flap, W: number, H_px: number, open: number) {
  const b = Math.min(ys.length - 1, f.at + f.span)
  const top: number[][] = []
  for (let k = f.at; k <= b; k++) top.push([XS[k] * W, ys[k] * H_px])
  const A = top[0]
  const B = top[top.length - 1]
  const mx = (A[0] + B[0]) / 2
  const my = (A[1] + B[1]) / 2
  const L = f.len * H_px * Math.max(0, open)
  const tip = [mx + f.skew * L, my + f.dir * L]
  const side = (P: number[], Q: number[], j0: number, j1: number) => {
    const dx = Q[0] - P[0]
    const dy = Q[1] - P[1]
    const len = Math.hypot(dx, dy) || 1
    const nx = -dy / len
    const ny = dx / len
    return [
      [P[0] + dx / 3 + nx * j0 * 0.22 * L, P[1] + dy / 3 + ny * j0 * 0.22 * L],
      [P[0] + (dx * 2) / 3 + nx * j1 * 0.22 * L, P[1] + (dy * 2) / 3 + ny * j1 * 0.22 * L],
    ]
  }
  return [...top, ...side(B, tip, f.jag[0], f.jag[1]), tip, ...side(tip, A, f.jag[2], f.jag[3])]
}

const n1 = (v: number) => Math.round(v * 10) / 10

export function polyD(pts: number[][]) {
  return pts.length ? "M" + pts.map((p) => n1(p[0]) + " " + n1(p[1])).join("L") + "Z" : ""
}

export function sheetD(ys: number[], W: number, H_px: number, off?: number[]) {
  let d = "M-20 -20L" + (W + 20) + " -20"
  for (let i = ys.length - 1; i >= 0; i--) d += "L" + n1(XS[i] * W) + " " + n1(ys[i] * H_px - (off ? off[i] : 0))
  return d + "Z"
}

export function mixHex(a: string, b: string, t: number) {
  const p = (hStr: string) => {
    const s = hStr.replace(String.fromCharCode(35), "")
    const f = s.length === 3 ? s.split("").map((c) => c + c).join("") : s.padEnd(6, "0").slice(0, 6)
    return [0, 2, 4].map((i) => parseInt(f.slice(i, i + 2), 16) || 0)
  }
  const x = p(a)
  const y = p(b)
  const k = clamp01(t)
  return String.fromCharCode(35) + x.map((v, i) => Math.round(v + (y[i] - v) * k).toString(16).padStart(2, "0")).join("")
}
// #endregion

export type IconName = "globe" | "shield" | "doc" | "chevL" | "squiggle" | "arrow"

export type TearLink = { label: string; href?: string }
export type FooterColumn = { title: string; links: TearLink[] }

export type PushpinTearFooterProps = {
  brand?: string
  tagline?: string
  description?: string
  serviceArea?: string
  columns?: FooterColumn[]
  copyright?: string
  sheet?: string
  paper?: string
  sheetInk?: string
  paperInk?: string
  seed?: number
  height?: number | string
  fontSans?: string
  onRepin?: (seed: number) => void
  className?: string
}

const P = { fill: "none", stroke: "currentColor", strokeWidth: 1.8, strokeLinecap: "round", strokeLinejoin: "round" } as const

function Icon({ name }: { name: IconName }) {
  const svg = (children: React.ReactNode, filled?: boolean) => (
    <svg viewBox="0 0 24 24" className="tpf-ico" aria-hidden="true" {...(filled ? { fill: "currentColor" } : P)}>
      {children}
    </svg>
  )
  switch (name) {
    case "chevL":
      return svg(<path d="M14.5 5.5 8 12l6.5 6.5" />)
    case "arrow":
      return svg(<path d="M5 12h13M13 6.5 18.5 12 13 17.5" />)
    case "squiggle":
      return svg(<path d="M3.5 15c1.8-3.2 3.6-3.2 5.4 0s3.6 3.2 5.4 0 3.6-3.2 5.4 0" />)
    case "globe":
      return svg(
        <>
          <circle cx="12" cy="12" r="8.5" />
          <path d="M3.5 12h17M12 3.5c2.5 2.6 3.6 5.4 3.6 8.5s-1.1 5.9-3.6 8.5c-2.5-2.6-3.6-5.4-3.6-8.5S9.5 6.1 12 3.5z" />
        </>
      )
    case "doc":
      return svg(
        <>
          <path d="M14 3.5H7.5a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2h9a2 2 0 0 0 2-2V8z" />
          <path d="M14 3.5V8h4.5M8.8 12.5h6.4M8.8 16h4.2" />
        </>
      )
    case "shield":
      return svg(
        <path
          fillRule="evenodd"
          d="M6.5 2.5h11a4 4 0 0 1 4 4v11a4 4 0 0 1-4 4h-11a4 4 0 0 1-4-4v-11a4 4 0 0 1 4-4zM12 6.2 7.6 7.8v3.6c0 2.9 1.9 5.2 4.4 6.2 2.5-1 4.4-3.3 4.4-6.2V7.8zm-2 5.8 1.4 1.4 2.8-2.9"
        />,
        true
      )
  }
}

/** The pushpin: glossy domed cap, waisted neck, flange, steel needle. Shaded from `base`. */
function Pin({ base, id }: { base: string; id: string }) {
  const hi = mixHex(base, H("ffffff"), 0.62)
  const mid = base
  const lo = mixHex(base, H("000000"), 0.42)
  const deep = mixHex(base, H("000000"), 0.66)
  const t = { transition: "stop-color .7s ease" }
  const side = (gid: string) => (
    <linearGradient id={gid} x1="0" x2="1" y1="0" y2="0">
      <stop offset="0" style={{ ...t, stopColor: lo }} />
      <stop offset=".24" style={{ ...t, stopColor: mid }} />
      <stop offset=".4" style={{ ...t, stopColor: hi }} />
      <stop offset=".58" style={{ ...t, stopColor: mid }} />
      <stop offset="1" style={{ ...t, stopColor: deep }} />
    </linearGradient>
  )
  return (
    <svg viewBox="0 0 100 200" className="tpf-pin-svg" aria-hidden="true">
      <defs>
        {side(id + "s")}
        <radialGradient id={id + "t"} cx=".38" cy=".3" r=".8">
          <stop offset="0" style={{ ...t, stopColor: hi }} />
          <stop offset=".55" style={{ ...t, stopColor: mid }} />
          <stop offset="1" style={{ ...t, stopColor: lo }} />
        </radialGradient>
        <linearGradient id={id + "n"} x1="0" x2="1" y1="0" y2="0">
          <stop offset="0" stopColor={H("5b616b")} />
          <stop offset=".4" stopColor={H("f4f5f7")} />
          <stop offset=".7" stopColor={H("a3a9b2")} />
          <stop offset="1" stopColor={H("4a4f57")} />
        </linearGradient>
        <filter id={id + "b"} x="-50%" y="-50%" width="200%" height="200%">
          <feGaussianBlur stdDeviation="1.4" />
        </filter>
      </defs>
      <path d="M48.3 112h3.4l-.7 76-1 4-1-4z" fill={"url(#" + id + "n)"} />
      <ellipse cx="50" cy="191.5" rx="3.2" ry="1.1" fill={H("0b1a33")} opacity=".35" />
      <path d="M28 103v6.5a22 7 0 0 0 44 0V103z" fill={"url(#" + id + "s)"} />
      <ellipse cx="50" cy="103" rx="22" ry="7" fill={"url(#" + id + "t)"} />
      <path
        d="M41.5 38c1.6 16 4.6 26 3 36-1.8 10-13 19-14.6 29h40.2c-1.6-10-12.8-19-14.6-29-1.6-10 1.4-20 3-36z"
        fill={"url(#" + id + "s)"}
      />
      <path d="M16 22v14a34 13 0 0 0 68 0V22z" fill={"url(#" + id + "s)"} />
      <ellipse cx="50" cy="22" rx="34" ry="13" fill={"url(#" + id + "t)"} />
      <ellipse cx="50" cy="22" rx="33" ry="12.3" fill="none" stroke={H("ffffff")} strokeOpacity=".28" strokeWidth=".8" />
      <ellipse cx="37" cy="17.5" rx="12" ry="4" fill={H("ffffff")} opacity=".7" filter={"url(#" + id + "b)"} />
      <path
        d="M22 38c4 4.5 9.5 6.4 15 7"
        stroke={H("ffffff")}
        strokeOpacity=".35"
        strokeWidth="2"
        fill="none"
        strokeLinecap="round"
        filter={"url(#" + id + "b)"}
      />
      <path d="M45 50c.8 8 1.6 14 .8 22" stroke={H("ffffff")} strokeOpacity=".45" strokeWidth="1.6" fill="none" strokeLinecap="round" />
    </svg>
  )
}

function useReducedMotion() {
  const [reduced, setReduced] = React.useState(false)
  React.useEffect(() => {
    if (typeof matchMedia !== "function") return
    const mq = matchMedia("(prefers-reduced-motion: reduce)")
    if (!mq) return
    const onMq = () => setReduced(Boolean(mq.matches))
    onMq()
    mq.addEventListener?.("change", onMq)
    return () => mq.removeEventListener?.("change", onMq)
  }, [])
  return reduced
}

const CSS =
  ".tpf{position:relative;overflow:hidden;container-type:size;min-height:660px;background:var(--tpf-sheet);color:var(--tpf-on-sheet);font-family:var(--tpf-sans);isolation:isolate;-webkit-font-smoothing:antialiased;text-rendering:optimizeLegibility;--ease:cubic-bezier(.22,1,.36,1);--fs:max(12px,1.75cqmin)}" +
  ".tpf *,.tpf *::before,.tpf *::after{box-sizing:border-box}" +
  ":where(.tpf) a{color:inherit;text-decoration:none}" +
  ":where(.tpf) button{font:inherit;color:inherit;background:none;border:0;padding:0;margin:0;cursor:pointer}" +
  ":where(.tpf) :focus-visible{outline:1.5px solid currentColor;outline-offset:4px;border-radius:4px}" +
  ".tpf-stage{position:absolute;left:0;top:0;max-width:none;display:block;pointer-events:none;z-index:0}" +
  ".tpf.is-hit .tpf-sheet-g{animation:tpf-hit .32s cubic-bezier(.3,.7,.4,1)}" +
  ".tpf-sheet{fill:var(--tpf-sheet)}.tpf-paper{fill:var(--tpf-paper)}.tpf-rim{fill:var(--tpf-rim)}" +
  ".tpf-ico{width:1.25em;height:1.25em;display:block;max-width:none;flex:none}" +
  ".tpf-z{position:absolute;z-index:2}" +
  ".tpf-ink{color:var(--tpf-on-paper)}" +
  ".tpf-h{transition:opacity .7s var(--ease),translate .8s var(--ease),filter .7s var(--ease);transition-delay:calc(var(--i,0)*45ms)}" +
  ".tpf:not(.s1) .tpf-h{opacity:0;translate:0 10px;filter:blur(6px)}" +
  ".tpf-p{transition:opacity .8s var(--ease),translate .9s var(--ease),filter .8s var(--ease);transition-delay:calc(480ms + var(--i,0)*70ms)}" +
  ".tpf:not(.s3) .tpf-p{opacity:0;translate:0 18px;filter:blur(8px);transition-delay:0s}" +
  // top bar
  ".tpf-top{left:0;right:0;top:7.5%;transform:translateY(-50%);display:flex;align-items:center;justify-content:space-between;padding:0 8%;font-size:max(14px,2.1cqmin)}" +
  ".tpf-row{display:flex;align-items:center;gap:.75em}" +
  ".tpf-ib{display:grid;place-items:center;width:2em;height:2em;border-radius:50%;background:color-mix(in srgb,currentColor 14%,transparent);transition:transform .35s var(--ease),background-color .25s}" +
  ".tpf-ib:hover{transform:translateY(-2px);background:color-mix(in srgb,currentColor 24%,transparent)}" +
  ".tpf-ib:active{transform:scale(.9)}" +
  ".tpf-up .tpf-ico{transform:rotate(90deg)}" +
  ".tpf-brand-pill{display:inline-flex;align-items:center;gap:.5em;font-weight:700;letter-spacing:-.02em;font-size:max(16px,2.3cqmin)}" +
  ".tpf-z-circle{width:1.65em;height:1.65em;border-radius:50%;display:grid;place-items:center;background:var(--tpf-on-sheet);color:var(--tpf-sheet);font-family:Georgia,serif;font-weight:700;font-size:.9em}" +
  ".tpf-tagline-top{display:inline-flex;align-items:center;gap:.55em;font-size:max(12px,1.8cqmin);font-weight:600;letter-spacing:.02em;padding:.38em .9em;border-radius:99px;background:color-mix(in srgb,currentColor 14%,transparent)}" +
  ".tpf-live{position:relative;width:.5em;height:.5em;border-radius:50%;background:currentColor}" +
  ".tpf-live::after{content:'';position:absolute;inset:0;border-radius:50%;border:1.5px solid currentColor;animation:tpf-ping 2.2s var(--ease) infinite}" +
  ".tpf-area-top{display:inline-flex;align-items:center;gap:.45em;font-size:max(12px,1.7cqmin);font-weight:500;opacity:.92}" +
  // description
  ".tpf-desc{left:50%;top:15.5%;transform:translate(-50%,-50%);width:min(84%,680px);text-align:center;font-size:max(12.5px,1.8cqmin);line-height:1.55;opacity:.94;margin:0}" +
  // 4-column grid on the orange sheet
  ".tpf-grid{left:8%;right:8%;top:23.5%;display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:max(16px,2.5cqw);font-size:var(--fs)}" +
  ".tpf-col{display:grid;align-content:start;gap:max(5px,.8cqmin)}" +
  ".tpf-col h3{all:unset;display:inline-flex;align-items:center;gap:.55em;font-weight:700;font-size:max(12.5px,1.85cqmin);letter-spacing:.03em;text-transform:uppercase;margin-bottom:max(3px,.4cqmin);opacity:.96}" +
  ".tpf-col h3 .tpf-ico{width:1em;height:1em;opacity:.8}" +
  ".tpf-col ul{all:unset;display:grid;gap:max(4px,.65cqmin)}" +
  ".tpf-col li{list-style:none;line-height:1.35}" +
  ".tpf-col li a{display:inline-block;font-weight:500;opacity:.92;transition:transform .3s var(--ease),opacity .2s}" +
  ".tpf-col li a:hover{transform:translateX(4px);opacity:1;text-decoration:underline;text-underline-offset:.2em}" +
  ".tpf-col li .tpf-static{display:inline-block;opacity:.75;cursor:default}" +
  // pin
  ".tpf-pin{position:absolute;z-index:3;left:50%;top:64%;width:max(46px,8.8cqmin);height:max(92px,17.6cqmin);transform:translate(-50%,-100%);cursor:pointer;-webkit-tap-highlight-color:transparent;touch-action:manipulation}" +
  ".tpf-pin:focus-visible{outline-offset:6px}" +
  ".tpf-tilt{position:absolute;inset:0;transform-origin:50% 100%;transform:rotate(calc(5deg + var(--tilt,0deg)));transition:transform .7s var(--ease)}" +
  ".tpf-drop,.tpf-body{position:absolute;inset:0;transform-origin:50% 100%}" +
  ".tpf-body{transition:transform .32s var(--ease)}" +
  ".tpf-pin-svg{position:absolute;inset:0;width:100%;height:100%;max-width:none;display:block;overflow:visible}" +
  ".tpf-shadow{position:absolute;right:50%;bottom:-.5%;width:150%;height:24%;transform-origin:100% 50%;transform:rotate(16deg);transition:transform .45s var(--ease),opacity .45s}" +
  ".tpf-shadow::before,.tpf-shadow::after{content:'';position:absolute;top:50%;border-radius:50%;transform:translateY(-50%)}" +
  `.tpf-shadow::before{right:0;width:100%;height:24%;background:linear-gradient(90deg,transparent,${C_ALPHA("9,22,48,.26")} 60%,${C_ALPHA("9,22,48,.42")});filter:blur(2px)}` +
  `.tpf-shadow::after{left:-6%;width:60%;height:100%;background:radial-gradient(closest-side,${C_ALPHA("9,22,48,.22")},transparent);filter:blur(3px)}` +
  ".tpf:not(.s2) .tpf-pin{opacity:0;pointer-events:none}" +
  ".tpf.s2 .tpf-drop{animation:tpf-drop .62s cubic-bezier(.5,0,.75,0) both}" +
  ".tpf.s2 .tpf-shadow{animation:tpf-shade .62s cubic-bezier(.5,0,.75,0) both}" +
  ".tpf-pin:hover .tpf-body{transform:translateY(-3%)}" +
  ".tpf-pin:hover .tpf-shadow{transform:rotate(16deg) translateX(-5%) scaleX(1.06)}" +
  ".tpf-pin[data-pin=lift] .tpf-body{transform:translateY(-22%) rotate(-10deg);transition-duration:.3s}" +
  ".tpf-pin[data-pin=lift] .tpf-shadow{transform:rotate(16deg) translateX(-26%) scale(1.25);opacity:.55}" +
  ".tpf-pin[data-pin=stab] .tpf-body{animation:tpf-stab .42s cubic-bezier(.55,0,.8,.2) both}" +
  // the paper's print underneath the tear
  ".tpf-brand{left:50%;top:67.5%;width:max-content;transform:translateX(-50%);display:grid;justify-items:center}" +
  ".tpf-brand-top{display:flex;align-items:center;justify-content:center;gap:.8em;padding:0 .3em;margin-bottom:.15em}" +
  ".tpf-eye{font-size:max(13px,2.1cqmin);font-weight:600;letter-spacing:.01em;background:linear-gradient(90deg,var(--tpf-on-paper),color-mix(in srgb,var(--tpf-on-paper) 55%,var(--tpf-paper)));-webkit-background-clip:text;background-clip:text;color:transparent}" +
  ".tpf-word{display:block;overflow:hidden;padding:0 .04em .14em;font-size:max(44px,min(11.5cqmin,15cqw));font-weight:800;letter-spacing:-.05em;line-height:.98;white-space:nowrap;color:var(--tpf-on-paper)}" +
  ".tpf-word span{display:block;position:relative;transition:transform 1s var(--ease) .5s}" +
  ".tpf-word span::after{content:'';position:absolute;left:.02em;right:.04em;bottom:-.02em;height:max(2.5px,.05em);background:var(--tpf-sheet);transform-origin:0 50%;transition:transform .9s var(--ease) 1.05s,height .3s var(--ease)}" +
  ".tpf:not(.s3) .tpf-word span{transform:translateY(125%);transition-delay:0s}" +
  ".tpf:not(.s3) .tpf-word span::after{transform:scaleX(0);transition-delay:0s}" +
  ".tpf-word:hover span::after{height:max(4px,.075em)}" +
  ".tpf-side{top:77%;display:grid;justify-items:center;gap:max(6px,1.2cqmin);font-size:max(11.5px,1.75cqmin);font-weight:600;transform:translateX(-50%);opacity:.82}" +
  ".tpf-side .tpf-ico{width:1.3em;height:1.3em;color:var(--tpf-sheet)}" +
  ".tpf-foot{left:0;right:0;bottom:4.5%;display:flex;align-items:center;justify-content:space-between;gap:1.5em;padding:0 10%;font-size:max(11.5px,1.7cqmin);font-weight:500}" +
  ".tpf-credit{position:absolute;left:50%;transform:translateX(-50%);font-size:max(10px,1.35cqmin);letter-spacing:.08em;text-transform:uppercase;opacity:.68;white-space:nowrap}" +
  // tablet & mobile responsive layout
  "@container (max-width: 780px){" +
  ".tpf{min-height:740px}" +
  ".tpf-top{padding:0 6%;top:6%}.tpf-tagline-top{display:none}" +
  ".tpf-desc{top:12.5%;width:88%;font-size:12px}" +
  ".tpf-grid{top:18.5%;left:6%;right:6%;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px 20px}" +
  ".tpf-side{top:85%}.tpf-side.l{left:16%!important}.tpf-side.r{left:84%!important}" +
  ".tpf-foot{padding:0 6%;bottom:3%}.tpf-credit{display:none}" +
  "}" +
  "@keyframes tpf-hit{0%{transform:translateY(0)}30%{transform:translateY(2px)}60%{transform:translateY(-.5px)}100%{transform:translateY(0)}}" +
  "@keyframes tpf-drop{0%{transform:translateY(-62cqh) rotate(-28deg) scale(1.5);opacity:0}25%{opacity:1}80%{transform:translateY(0) rotate(3deg) scale(1)}90%{transform:scale(1.04,.95)}100%{transform:none;opacity:1}}" +
  "@keyframes tpf-shade{0%{transform:rotate(16deg) translateX(-60%) scale(2.4);opacity:0}80%{transform:rotate(16deg);opacity:1}100%{transform:rotate(16deg);opacity:1}}" +
  "@keyframes tpf-stab{0%{transform:translateY(-22%) rotate(-10deg)}70%{transform:translateY(1.5%) rotate(1deg)}100%{transform:none}}" +
  "@keyframes tpf-ping{0%{transform:scale(1);opacity:.9}80%,100%{transform:scale(2.6);opacity:0}}" +
  "@media (prefers-reduced-motion: reduce){.tpf *,.tpf *::before,.tpf *::after{animation:none!important;transition-duration:.01s!important;transition-delay:0s!important}}"

const ZENTRO_COLUMNS: FooterColumn[] = [
  {
    title: "Quick Links",
    links: [
      { label: "Home", href: "/" },
      { label: "Send Parcel", href: "/register" },
      { label: "Track Parcel", href: "/track" },
      { label: "Our Services", href: "#key-features" },
    ],
  },
  {
    title: "For Business",
    links: [
      { label: "Merchant Registration", href: "/register/merchant" },
      { label: "Merchant Login", href: "/merchant/login" },
      { label: "Business Shipping", href: "#businesses" },
      { label: "API Integration", href: "/merchant" },
    ],
  },
  {
    title: "Support",
    links: [
      { label: "Delivery FAQ", href: "#faqs" },
      { label: "Help Center" },
      { label: "Report an Issue" },
    ],
  },
  {
    title: "Legal",
    links: [
      { label: "Privacy Policy" },
      { label: "Terms & Conditions" },
    ],
  },
]

function NavItem({ item }: { item: TearLink }) {
  if (!item.href) {
    return <span className="tpf-static">{item.label}</span>
  }
  if (item.href === "#faqs" || item.href === "#faq") {
    return (
      <a
        href={item.href}
        onClick={(e) => {
          e.preventDefault();
          window.dispatchEvent(new CustomEvent('zentro:open-chatbot'));
        }}
      >
        {item.label}
      </a>
    );
  }
  if (item.href.startsWith("/")) {
    return <Link to={item.href}>{item.label}</Link>
  }
  return <a href={item.href}>{item.label}</a>
}

export default function PushpinTearFooter({
  brand = "ZENTRO.",
  tagline = "Delivering Trust, Every Mile.",
  description = "Your trusted parcel delivery partner across Sri Lanka. Book deliveries, track shipments in real time, and manage parcels with ease.",
  serviceArea = "Serving customers across Sri Lanka.",
  columns = ZENTRO_COLUMNS,
  copyright = "© 2026 Zentro. All Rights Reserved.",
  sheet = H("F97316"),
  paper = H("FFF7ED"),
  sheetInk = H("FFFFFF"),
  paperInk = H("0F172A"),
  seed = 11,
  height = "680px",
  fontSans = "'Plus Jakarta Sans', 'Outfit', system-ui, sans-serif",
  onRepin,
  className = "",
}: PushpinTearFooterProps) {
  const uid = React.useId().replace(/[^a-zA-Z0-9]/g, "")
  const rootRef = React.useRef<HTMLElement>(null)
  const svgRef = React.useRef<SVGSVGElement>(null)
  const sheetRef = React.useRef<SVGPathElement>(null)
  const rimRef = React.useRef<SVGPathElement>(null)
  const flapRefs = React.useRef<(SVGPathElement | null)[]>([])
  const gradRefs = React.useRef<(SVGLinearGradientElement | null)[]>([])
  const pinRef = React.useRef<HTMLButtonElement>(null)
  const timers = React.useRef<number[]>([])

  const still = useReducedMotion()
  const [stage, setStage] = React.useState(0)
  const [hit, setHit] = React.useState(false)
  const [visible, setVisible] = React.useState(false)
  const [pin, setPin] = React.useState<"idle" | "lift" | "stab">("idle")
  const [grain, setGrain] = React.useState("")

  const s = React.useRef({
    W: 1200,
    H: 680,
    tear: makeTear(seed),
    prev: null as Tear | null,
    from: [] as number[],
    ys: [] as number[],
    mode: "wait" as "wait" | "intro" | "morph" | "rest",
    t0: 0,
    dur: 1,
    raf: 0,
    px: -1e4,
    py: -1e4,
    lift: new Array(FLAPS).fill(0) as number[],
    still: false,
    busy: false,
    seed,
  }).current
  s.still = still

  const later = (fn: () => void, ms: number) => {
    timers.current.push(window.setTimeout(fn, ms))
  }

  // ---- drawing --------------------------------------------------------------
  const frame = React.useCallback(
    (now: number) => {
      s.raf = 0
      const { W, H: H_px, tear } = s
      if (!W || !H_px) return
      let busy = false
      let ys: number[]
      const open = new Array(FLAPS).fill(1)
      const openOld = new Array(FLAPS).fill(0)
      if (s.mode === "wait") {
        ys = tear.ys.map(() => START)
        open.fill(0)
      } else if (s.mode === "intro") {
        const p = (now - s.t0) / s.dur
        ys = introYs(tear, Math.min(1, p))
        tear.flaps.forEach((f, k) => (open[k] = flapOpen(tear, f, Math.min(1, p))))
        if (p >= 1) s.mode = "rest"
        else busy = true
      } else if (s.mode === "morph") {
        const p = (now - s.t0) / s.dur
        ys = morphYs(s.from, tear, Math.min(1, p))
        open.fill(easeTear((p - 0.5) / 0.5))
        openOld.fill(1 - easeInOut(p / 0.35))
        if (p >= 1) s.mode = "rest"
        else busy = true
      } else ys = tear.ys
      s.ys = ys

      tear.flaps.forEach((f, k) => {
        const b = Math.min(ys.length - 1, f.at + f.span)
        const mx = ((XS[f.at] + XS[b]) / 2) * W
        const my = ((ys[f.at] + ys[b]) / 2) * H_px
        const target = s.still ? 0 : clamp01(1 - Math.hypot(s.px - mx, s.py - my) / (0.2 * Math.max(W, 520)))
        s.lift[k] += (target - s.lift[k]) * 0.14
        if (Math.abs(target - s.lift[k]) > 0.003) busy = true
        open[k] *= 1 + 0.32 * s.lift[k]
      })

      sheetRef.current?.setAttribute("d", sheetD(ys, W, H_px, tear.rim))
      rimRef.current?.setAttribute("d", sheetD(ys, W, H_px))
      const draw = (k: number, f: Flap | undefined, o: number) => {
        const el = flapRefs.current[k]
        const g = gradRefs.current[k]
        if (!el || !g) return
        if (!f || o < 0.01) return el.setAttribute("d", "")
        const pts = flapPoly(ys, f, W, H_px, o)
        el.setAttribute("d", polyD(pts))
        const b = Math.min(ys.length - 1, f.at + f.span)
        const tip = pts[f.span + 3]
        g.setAttribute("x1", String(((XS[f.at] + XS[b]) / 2) * W))
        g.setAttribute("y1", String(((ys[f.at] + ys[b]) / 2) * H_px))
        g.setAttribute("x2", String(tip[0]))
        g.setAttribute("y2", String(tip[1]))
      }
      for (let k = 0; k < FLAPS; k++) {
        draw(k, tear.flaps[k], open[k])
        draw(FLAPS + k, s.prev?.flaps[k], openOld[k])
      }
      if (busy && typeof requestAnimationFrame !== "undefined") s.raf = requestAnimationFrame(frame)
    },
    [s]
  )

  const kick = React.useCallback(() => {
    if (!s.raf && typeof requestAnimationFrame !== "undefined") s.raf = requestAnimationFrame(frame)
  }, [s, frame])

  // size
  React.useEffect(() => {
    const el = rootRef.current
    if (!el) return
    const updateSize = () => {
      s.W = el.clientWidth || 1200
      s.H = el.clientHeight || 680
      const svg = svgRef.current
      if (svg) {
        svg.setAttribute("width", String(s.W))
        svg.setAttribute("height", String(s.H))
        svg.setAttribute("viewBox", "0 0 " + s.W + " " + s.H)
      }
      kick()
    }
    updateSize()
    if (typeof ResizeObserver === "undefined") return
    const ro = new ResizeObserver(updateSize)
    ro.observe(el)
    return () => {
      ro.disconnect()
      if (typeof cancelAnimationFrame !== "undefined") cancelAnimationFrame(s.raf)
      s.raf = 0
    }
  }, [s, kick])

  // visibility
  React.useEffect(() => {
    const el = rootRef.current
    if (!el || typeof IntersectionObserver === "undefined") {
      setVisible(true)
      return
    }
    const io = new IntersectionObserver(([e]) => setVisible(e.isIntersecting), { threshold: 0.18 })
    io.observe(el)
    return () => io.disconnect()
  }, [])

  // grain tile, once
  React.useEffect(() => {
    if (typeof navigator !== "undefined" && /jsdom/i.test(navigator.userAgent)) return
    try {
      const c = document.createElement("canvas")
      c.width = c.height = 160
      const g = c.getContext?.("2d")
      if (!g) return
      const img = g.createImageData(160, 160)
      const r = rng(42)
      for (let i = 0; i < img.data.length; i += 4) {
        const v = r() < 0.5 ? 0 : 255
        img.data[i] = img.data[i + 1] = img.data[i + 2] = v
        img.data[i + 3] = Math.floor(r() * 42)
      }
      g.putImageData(img, 0, 0)
      setGrain(c.toDataURL())
    } catch {
      // ignore in headless test environments without canvas implementation
    }
  }, [])

  // the opening: chrome, pin, rip
  React.useEffect(() => {
    if (!visible || stage > 0) return
    if (still) {
      setStage(3)
      s.mode = "rest"
      kick()
      return
    }
    setStage(1)
    later(() => setStage(2), 640)
    later(() => {
      setHit(true)
      setStage(3)
      s.mode = "intro"
      s.t0 = performance.now()
      s.dur = 1550
      kick()
    }, 1140)
    later(() => setHit(false), 1480)
  }, [visible, stage, still, s, kick])

  React.useEffect(
    () => () => {
      timers.current.forEach((id) => clearTimeout(id))
    },
    []
  )

  React.useEffect(() => {
    if (seed === s.seed) return
    s.seed = seed
    s.prev = s.tear
    s.from = s.ys.length ? s.ys : s.tear.ys
    s.tear = makeTear(seed)
    if (s.mode !== "wait") {
      s.mode = still ? "rest" : "morph"
      s.t0 = performance.now()
      s.dur = 1100
    }
    kick()
  }, [seed, still, s, kick])

  // ---- actions ----------------------------------------------------------------
  const onPointerMove = (e: React.PointerEvent) => {
    const el = rootRef.current
    if (!el || s.still) return
    const r = el.getBoundingClientRect()
    s.px = e.clientX - r.left
    s.py = e.clientY - r.top
    const tilt = Math.max(-9, Math.min(9, (s.px / r.width - 0.5) * 18))
    pinRef.current?.style.setProperty("--tilt", tilt.toFixed(2) + "deg")
    kick()
  }
  const onPointerLeave = () => {
    s.px = s.py = -1e4
    pinRef.current?.style.setProperty("--tilt", "0deg")
    kick()
  }

  const repin = () => {
    if (s.busy || stage < 3) return
    const next = s.seed + 1
    const swap = () => {
      s.seed = next
      s.prev = s.tear
      s.from = s.ys.length ? s.ys : s.tear.ys
      s.tear = makeTear(next)
    }
    if (still) {
      swap()
      s.prev = null
      s.mode = "rest"
      kick()
      onRepin?.(next)
      return
    }
    s.busy = true
    setPin("lift")
    later(() => {
      swap()
      s.mode = "morph"
      s.t0 = performance.now()
      s.dur = 1150
      kick()
    }, 300)
    later(() => setPin("stab"), 1280)
    later(() => {
      setPin("idle")
      s.busy = false
      onRepin?.(next)
    }, 1720)
  }

  const toTop = () => window.scrollTo({ top: 0, behavior: still ? "auto" : "smooth" })

  // ---- colours ----------------------------------------------------------------
  const pinBase = sheet || H("F97316")
  const flapBase = mixHex(paper || H("FFF7ED"), H("ffffff"), 0.35)
  const vars = {
    height: typeof height === "number" ? height + "px" : height,
    "--tpf-sheet": sheet,
    "--tpf-paper": paper,
    "--tpf-on-sheet": sheetInk,
    "--tpf-on-paper": paperInk ?? H("0F172A"),
    "--tpf-rim": mixHex(flapBase, H("ffffff"), 0.6),
    "--tpf-sans": fontSans,
  } as React.CSSProperties

  const idxStyle = (i: number) => ({ "--i": i }) as React.CSSProperties

  const cls = ["tpf", stage >= 1 && "s1", stage >= 2 && "s2", stage >= 3 && "s3", hit && "is-hit", className]
    .filter(Boolean)
    .join(" ")

  return (
    <footer ref={rootRef} className={cls} style={vars} onPointerMove={onPointerMove} onPointerLeave={onPointerLeave}>
      <style>{CSS}</style>

      <svg ref={svgRef} className="tpf-stage" aria-hidden="true">
        <defs>
          <radialGradient id={uid + "v"} cx=".5" cy=".78" r=".75">
            <stop offset=".45" stopColor={H("ffffff")} stopOpacity="0" />
            <stop offset="1" stopColor={H("0d1c38")} stopOpacity=".07" />
          </radialGradient>
          <linearGradient id={uid + "l"} x1="0" y1="0" x2=".35" y2="1">
            <stop offset="0" stopColor={H("ffffff")} stopOpacity=".14" />
            <stop offset=".55" stopColor={H("ffffff")} stopOpacity="0" />
            <stop offset="1" stopColor={H("001033")} stopOpacity=".12" />
          </linearGradient>
          {grain && (
            <pattern id={uid + "g"} width="160" height="160" patternUnits="userSpaceOnUse">
              <image href={grain} width="160" height="160" />
            </pattern>
          )}
          <filter id={uid + "d"} x="-5%" y="-5%" width="110%" height="120%">
            <feDropShadow dx="0" dy="3" stdDeviation="3.5" floodColor={H("0a1a3a")} floodOpacity=".3" />
          </filter>
          <filter id={uid + "f"} x="-30%" y="-30%" width="160%" height="170%">
            <feDropShadow dx="-2" dy="4" stdDeviation="4" floodColor={H("0a1a3a")} floodOpacity=".26" />
          </filter>
          {Array.from({ length: FLAPS * 2 }, (_, k) => (
            <linearGradient
              key={k}
              id={uid + "fg" + k}
              ref={(el) => {
                gradRefs.current[k] = el
              }}
              gradientUnits="userSpaceOnUse"
            >
              <stop offset="0" stopColor={mixHex(flapBase, H("1c2a44"), 0.34)} />
              <stop offset=".5" stopColor={mixHex(flapBase, H("1c2a44"), 0.08)} />
              <stop offset="1" stopColor={H("ffffff")} />
            </linearGradient>
          ))}
          <path id={uid + "s"} ref={sheetRef} />
        </defs>
        <rect className="tpf-paper" width="100%" height="100%" />
        <rect width="100%" height="100%" fill={"url(#" + uid + "v)"} style={{ mixBlendMode: "multiply" }} />
        {grain && <rect width="100%" height="100%" fill={"url(#" + uid + "g)"} opacity=".32" />}
        <g className="tpf-sheet-g" filter={"url(#" + uid + "d)"}>
          <path ref={rimRef} className="tpf-rim" />
          <use href={"#" + uid + "s"} className="tpf-sheet" />
        </g>
        <use href={"#" + uid + "s"} fill={"url(#" + uid + "l)"} />
        {grain && <use href={"#" + uid + "s"} fill={"url(#" + uid + "g)"} opacity=".5" />}
        <g filter={"url(#" + uid + "f)"}>
          {Array.from({ length: FLAPS * 2 }, (_, k) => (
            <path
              key={k}
              ref={(el) => {
                flapRefs.current[k] = el
              }}
              fill={"url(#" + uid + "fg" + k + ")"}
            />
          ))}
        </g>
      </svg>

      {/* ---- Top bar on the Zentro orange sheet ---- */}
      <div className="tpf-z tpf-top">
        <div className="tpf-row tpf-h" style={idxStyle(0)}>
          <button type="button" className="tpf-ib tpf-up" onClick={toTop} aria-label="Back to top" title="Back to top">
            <Icon name="chevL" />
          </button>
          <Link to="/" className="tpf-brand-pill" aria-label="Zentro home">
            <span className="tpf-z-circle" aria-hidden="true">
              z
            </span>
            <span>{brand}</span>
          </Link>
        </div>

        <div className="tpf-tagline-top tpf-h" style={idxStyle(1)}>
          <i className="tpf-live" aria-hidden="true" />
          <span>{tagline}</span>
        </div>

        <div className="tpf-area-top tpf-h" style={idxStyle(2)}>
          <Icon name="globe" />
          <span>{serviceArea}</span>
        </div>
      </div>

      {/* ---- Brand description on the orange sheet ---- */}
      <p className="tpf-z tpf-desc tpf-h" style={idxStyle(3)}>
        {description}
      </p>

      {/* ---- 4-Column Responsive Zentro Footer Sections ---- */}
      <div className="tpf-z tpf-grid">
        {columns.map((c, i) => (
          <nav key={c.title} className="tpf-col tpf-h" style={idxStyle(4 + i)} aria-label={c.title}>
            <h3>
              <span>{c.title}</span>
              <Icon name="squiggle" />
            </h3>
            <ul>
              {c.links.map((l) => (
                <li key={l.label}>
                  <NavItem item={l} />
                </li>
              ))}
            </ul>
          </nav>
        ))}
      </div>

      {/* ---- The glossy pushpin ---- */}
      <button
        ref={pinRef}
        type="button"
        className="tpf-pin"
        data-pin={pin}
        onClick={repin}
        aria-label="Re-pin: tear a new edge"
        title="Click to re-tear the poster"
      >
        <span className="tpf-shadow" aria-hidden="true" />
        <span className="tpf-tilt">
          <span className="tpf-drop">
            <span className="tpf-body">
              <Pin base={pinBase} id={uid + "p"} />
            </span>
          </span>
        </span>
      </button>

      {/* ---- Printed on the warm cream paper underneath the tear ---- */}
      <div className="tpf-z tpf-brand tpf-ink">
        <div className="tpf-brand-top tpf-p" style={idxStyle(0)}>
          <span className="tpf-eye">{tagline}</span>
        </div>
        <Link to="/" className="tpf-word" aria-label="Zentro home">
          <span>{brand}</span>
        </Link>
      </div>

      <span className="tpf-z tpf-side tpf-ink tpf-p l" style={{ ...idxStyle(1), left: "24%" }}>
        <span>Privacy Policy</span>
        <Icon name="shield" />
      </span>
      <span className="tpf-z tpf-side tpf-ink tpf-p r" style={{ ...idxStyle(2), left: "76%" }}>
        <span>Terms &amp; Conditions</span>
        <Icon name="doc" />
      </span>

      <div className="tpf-z tpf-foot tpf-ink">
        <span className="tpf-p" style={idxStyle(3)}>
          {copyright}
        </span>
        <span className="tpf-credit tpf-p" style={idxStyle(4)}>
          SRI LANKA · ISLANDWIDE EXPRESS
        </span>
        <span className="tpf-p" style={idxStyle(5)}>
          {serviceArea}
        </span>
      </div>
    </footer>
  )
}
