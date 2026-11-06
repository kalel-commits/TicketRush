import { useId, useMemo } from 'react';
import type { Category } from './catalog';

/**
 * Generated concert artwork: stage light beams, haze and a crowd silhouette, coloured by genre and varied per event.
 * Inline SVG — no photographs, logos or likenesses of real artists, nothing to license and no image that can 404.
 */
const PALETTES: Record<Category | 'DEFAULT', [string, string, string]> = {
  POP: ['#ff2e93', '#7c3aed', '#22d3ee'],
  BOLLYWOOD: ['#f59e0b', '#e11d48', '#fde68a'],
  ROCK: ['#ef4444', '#f97316', '#94a3b8'],
  EDM: ['#06b6d4', '#a3e635', '#8b5cf6'],
  HIP_HOP: ['#facc15', '#fb923c', '#a855f7'],
  FESTIVAL: ['#fb7185', '#f97316', '#2dd4bf'],
  CLASSICAL: ['#2dd4bf', '#d4a373', '#6366f1'],
  DEFAULT: ['#818cf8', '#38bdf8', '#64748b'],
};

function hash(s: string) {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) h = Math.imul(h ^ s.charCodeAt(i), 16777619);
  return h >>> 0;
}

function rng(seed: number) {
  let a = seed;
  return () => {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const W = 1600;
const H = 900;

export function EventArt({ seed, category, className = '', crowd = true }:
  { seed: string; category?: Category | null; className?: string; crowd?: boolean }) {
  const uid = useId().replace(/:/g, '');
  const [c1, c2, c3] = PALETTES[category ?? 'DEFAULT'] ?? PALETTES.DEFAULT;
  const art = useMemo(() => {
    const r = rng(hash(seed));
    const beams = Array.from({ length: 5 + Math.floor(r() * 4) }, (_, i) => {
      const x = 120 + r() * (W - 240);
      const spread = 140 + r() * 260;
      const tilt = (r() - 0.5) * 900;
      return { x, spread, tilt, color: [c1, c2, c3][i % 3], o: 0.16 + r() * 0.22 };
    });
    const glows = Array.from({ length: 3 }, (_, i) => ({ x: r() * W, y: 120 + r() * 420, rr: 260 + r() * 360, color: [c1, c2, c3][i] }));
    // crowd: heads and raised hands along the bottom edge
    let d = `M0 ${H} L0 ${H - 90}`;
    for (let x = 0; x <= W; x += 18 + r() * 22) {
      const head = H - 95 - r() * 55;
      if (r() < 0.16) {
        const hx = x + 6;
        d += ` L${hx} ${head + 10} L${hx + 4} ${head - 70 - r() * 50} L${hx + 12} ${head - 70 - r() * 40} L${hx + 14} ${head + 8}`;
      }
      d += ` Q${x + 14} ${head - 26} ${x + 28} ${head + 4}`;
    }
    d += ` L${W} ${H} Z`;
    const sparks = Array.from({ length: 28 }, () => ({ x: r() * W, y: r() * 520, s: 1 + r() * 2.5, o: 0.25 + r() * 0.6 }));
    return { beams, glows, crowd: d, sparks, rot: Math.floor(r() * 360) };
  }, [seed, c1, c2, c3]);

  return (
    <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="xMidYMid slice" role="presentation" aria-hidden className={className}>
      <defs>
        <linearGradient id={`sky-${uid}`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#05060d" />
          <stop offset="0.6" stopColor="#0b0f22" />
          <stop offset="1" stopColor="#140b26" />
        </linearGradient>
        {art.glows.map((g, i) => (
          <radialGradient key={i} id={`glow-${uid}-${i}`}>
            <stop offset="0" stopColor={g.color} stopOpacity="0.55" />
            <stop offset="1" stopColor={g.color} stopOpacity="0" />
          </radialGradient>
        ))}
        {art.beams.map((b, i) => (
          <linearGradient key={i} id={`beam-${uid}-${i}`} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stopColor={b.color} stopOpacity={b.o * 2.4} />
            <stop offset="1" stopColor={b.color} stopOpacity="0" />
          </linearGradient>
        ))}
        <linearGradient id={`floor-${uid}`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor={c1} stopOpacity="0.5" />
          <stop offset="1" stopColor={c2} stopOpacity="0" />
        </linearGradient>
      </defs>
      <rect width={W} height={H} fill={`url(#sky-${uid})`} />
      {art.glows.map((g, i) => <circle key={i} cx={g.x} cy={g.y} r={g.rr} fill={`url(#glow-${uid}-${i})`} />)}
      {art.beams.map((b, i) => (
        <polygon key={i} points={`${b.x - 10},0 ${b.x + 10},0 ${b.x + b.tilt + b.spread},${H} ${b.x + b.tilt - b.spread},${H}`}
                 fill={`url(#beam-${uid}-${i})`} style={{ mixBlendMode: 'screen' }} />
      ))}
      {art.sparks.map((s, i) => <circle key={i} cx={s.x} cy={s.y} r={s.s} fill="#fff" opacity={s.o} />)}
      <ellipse cx={W / 2} cy={H - 120} rx={W * 0.6} ry={90} fill={`url(#floor-${uid})`} />
      {crowd && <path d={art.crowd} fill="#03040a" />}
    </svg>
  );
}
