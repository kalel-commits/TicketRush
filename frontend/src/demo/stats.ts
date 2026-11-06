export interface Sample { status: number; ms: number }

export interface Summary {
  total: number;
  byStatus: Record<number, number>;
  p50: number | null;
  p95: number | null;
  p99: number | null;
  max: number | null;
}

/** Nearest-rank percentiles over measured request latencies. */
export function summarize(samples: Sample[]): Summary {
  const byStatus: Record<number, number> = {};
  for (const s of samples) byStatus[s.status] = (byStatus[s.status] ?? 0) + 1;
  const sorted = samples.map((s) => s.ms).sort((a, b) => a - b);
  const pct = (p: number) => (sorted.length ? sorted[Math.max(0, Math.ceil((p / 100) * sorted.length) - 1)] : null);
  return { total: samples.length, byStatus, p50: pct(50), p95: pct(95), p99: pct(99), max: sorted.length ? sorted[sorted.length - 1] : null };
}
