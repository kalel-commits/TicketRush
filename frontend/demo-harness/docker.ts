import { execFile } from 'node:child_process';
import { REPO_ROOT, secret } from './env';

/**
 * Read-only access to the real stores through `docker compose exec` (internal ports are not published).
 * The MySQL password reaches the container through the environment, never the command line.
 */
function compose(args: string[], extraEnv: Record<string, string> = {}, timeoutMs = 15_000): Promise<string> {
  return new Promise((resolve, reject) => {
    execFile('docker', ['compose', ...args], { cwd: REPO_ROOT, timeout: timeoutMs, maxBuffer: 8 * 1024 * 1024,
      env: { ...process.env, ...extraEnv }, windowsHide: true }, (err, stdout, stderr) => {
      if (err) reject(new Error(`docker compose ${args.slice(0, 3).join(' ')} failed: ${(stderr || err.message).split('\n')[0]}`));
      else resolve(stdout.replace(/\r\n/g, '\n'));
    });
  });
}

/** SELECTs as root (test tooling, like load/invariants.sh). Callers pass only fixed SQL with numeric ids. */
export function mysql(sql: string): Promise<string> {
  return compose(['exec', '-T', '-e', 'MYSQL_PWD', 'mysql', 'mysql', '-uroot', '-N', '-B', '-e', sql],
    { MYSQL_PWD: secret('MYSQL_ROOT_PASSWORD') });
}

/** Runs a read-only Lua script: every lock key for the event with its owner token and remaining TTL. */
export function redisSeatLocks(eventId: number): Promise<string> {
  const lua = "local out = {} for _, k in ipairs(redis.call('KEYS', ARGV[1])) do " +
    "out[#out+1] = k; out[#out+1] = redis.call('GET', k) or ''; out[#out+1] = tostring(redis.call('PTTL', k)) end return out";
  return compose(['exec', '-T', 'redis', 'redis-cli', '--no-raw', 'EVAL', lua, '0', `bk:seatlock:${eventId}:*`]);
}

export function kafkaGroups(): Promise<string> {
  return compose(['exec', '-T', 'kafka', '/opt/kafka/bin/kafka-consumer-groups.sh', '--bootstrap-server', 'localhost:9092',
    '--describe', '--all-groups'], {}, 40_000);
}

/** A Micrometer counter from a service's actuator (inside its container; the port is not published). */
export async function metric(service: 'booking-service' | 'payment-service', port: number, name: string): Promise<number | null> {
  // summed over every replica (booking-service runs 2): `exec` alone only reaches replica 1
  let total: number | null = null;
  for (let index = 1; index <= 4; index++) {
    try {
      const out = await compose(['exec', '-T', '--index', String(index), service, 'curl', '-fs', `http://localhost:${port}/actuator/metrics/${name}`]);
      const m = JSON.parse(out).measurements?.find((x: { statistic: string }) => x.statistic === 'COUNT' || x.statistic === 'VALUE');
      if (typeof m?.value === 'number') total = (total ?? 0) + m.value;
    } catch (e) {
      if (/is not running container|no container/i.test((e as Error).message)) break;   // no such replica: done
      return null;   // a replica failed to answer: a partial sum would be wrong, so report "n/a"
    }
  }
  return total;
}

/** The fake provider's demo control endpoint (internal network only). */
export async function providerBehaviour(body?: Record<string, unknown>): Promise<Record<string, unknown>> {
  const args = ['exec', '-T', 'fake-payment-provider', 'curl', '-fs'];
  if (body) args.push('-X', 'POST', '-H', 'Content-Type: application/json', '-d', JSON.stringify(body));
  return JSON.parse(await compose([...args, 'http://localhost:8090/__control/behaviour']));
}
