/**
 * Classifies every load-test response by ITS OWN booking row (user id + Idempotency-Key), never by subtracting
 * totals (review I4). booking-service writes a row only after the Redis filter lets a request through.
 */
export interface Sent { userId: number; key: string; status: number; code?: string }
export interface Row { userId: number; key: string; status: string; reason: string | null }

export function classifyLosers(sent: Sent[], rows: Row[]) {
  const byKey = new Map(rows.map((r) => [`${r.userId}\t${r.key}`, r]));
  let redisRejected = 0;
  let mysqlRejected = 0;
  let otherFailures = 0;
  const mismatches: string[] = [];
  for (const s of sent) {
    if (s.status === 201) continue;
    const row = byKey.get(`${s.userId}\t${s.key}`);
    if (s.status === 409 && s.code === 'SEATS_UNAVAILABLE') {
      if (!row) redisRejected++;
      else if (row.status === 'FAILED' && row.reason === 'SEATS_UNAVAILABLE') mysqlRejected++;
      else mismatches.push(`user ${s.userId}: HTTP 409 but booking ${row.status}`);
    } else {
      otherFailures++;
    }
  }
  return { redisRejected, mysqlRejected, otherFailures, mismatches };
}
