/** Parsers for the harness's read-only `docker compose exec` readers. */

/** `mysql -B -N` output: tab-separated rows; SQL NULL arrives as `NULL` (or `\N`). The columns read here never hold the text "NULL". */
export function parseMysqlBatch(out: string, columns: string[]): Record<string, string | null>[] {
  const isNull = (v: string | undefined) => v === undefined || v === '\\N' || v === 'NULL';
  return out.split('\n').filter((l) => l.length > 0).map((line) => {
    const cells = line.split('\t');
    return Object.fromEntries(columns.map((c, i) => [c, isNull(cells[i]) ? null : cells[i]]));
  });
}

/** HEX(BINARY(16)) → canonical lowercase UUID, as the API returns it. */
export function hexToUuid(hex: string | null): string | null {
  if (!hex) return null;
  const h = hex.toLowerCase();
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20, 32)}`;
}

/** booking-service writes the lock value as `userId:idempotencyKey` (SeatLocks / acquire.lua). */
export function parseLockOwner(value: string): { userId: number; key: string } | null {
  const i = value.indexOf(':');
  if (i <= 0) return null;
  const userId = Number(value.slice(0, i));
  return Number.isInteger(userId) ? { userId, key: value.slice(i + 1) } : null;
}

/** `redis-cli --no-raw EVAL` reply of the lock script: numbered, quoted (key, owner, PTTL) triples. */
export function parseLockLines(out: string): { seatId: number; ownerUserId: number; ownerKey: string; pttlMs: number }[] {
  const items = out.split('\n').map((l) => l.trim()).filter((l) => l && l !== '(empty array)')
    .map((l) => l.replace(/^\d+\)\s*/, '').replace(/^"(.*)"$/, '$1'));
  const rows = [];
  for (let i = 0; i + 2 < items.length; i += 3) {
    const owner = parseLockOwner(items[i + 1]);
    const seatId = Number(items[i].split(':').at(-1));
    if (owner && Number.isFinite(seatId)) rows.push({ seatId, ownerUserId: owner.userId, ownerKey: owner.key, pttlMs: Number(items[i + 2]) });
  }
  return rows.sort((a, b) => a.seatId - b.seatId);
}

/** kafka-consumer-groups --describe: GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG …, summed per group+topic. */
export function parseGroups(out: string): { group: string; topic: string; lag: number }[] {
  const acc = new Map<string, number>();
  for (const line of out.split('\n')) {
    const c = line.trim().split(/\s+/);
    if (c.length < 6 || c[0] === 'GROUP' || !/^\d+$/.test(c[2])) continue;
    const lag = c[5] === '-' ? 0 : Number(c[5]);
    const key = `${c[0]}\t${c[1]}`;
    acc.set(key, (acc.get(key) ?? 0) + (Number.isFinite(lag) ? lag : 0));
  }
  return [...acc].map(([k, lag]) => ({ group: k.split('\t')[0], topic: k.split('\t')[1], lag }));
}
