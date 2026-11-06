import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { randomBytes } from 'node:crypto';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/** Server-side only. Nothing returned from here may ever be sent to the browser. */
export const HARNESS_DIR = dirname(fileURLToPath(import.meta.url));
export const REPO_ROOT = join(HARNESS_DIR, '..', '..');
export const GATEWAY = process.env.DEMO_GATEWAY ?? 'http://localhost:8080';

function readEnvFile(): Record<string, string> {
  const file = join(REPO_ROOT, '.env');
  if (!existsSync(file)) return {};
  return Object.fromEntries(readFileSync(file, 'utf8').split(/\r?\n/)
    .filter((l) => l.includes('=') && !l.trimStart().startsWith('#'))
    .map((l) => [l.slice(0, l.indexOf('=')).trim(), l.slice(l.indexOf('=') + 1).trim()]));
}

export function secret(name: 'ADMIN_EMAIL' | 'ADMIN_PASSWORD' | 'MYSQL_ROOT_PASSWORD'): string {
  const v = process.env[name] ?? readEnvFile()[name];
  if (!v) throw new Error(`${name} is not set in .env`);
  return v;
}

/**
 * Password for the demo buyer accounts: random, generated once and kept outside the repo and the Vite root
 * (~/.ticketrush-demo/state.json), so the dev server can never serve it (review I3) and re-runs reuse the accounts.
 */
export function stateFile(): string {
  return join(process.env.TICKETRUSH_DEMO_STATE_DIR ?? join(homedir(), '.ticketrush-demo'), 'state.json');
}

export function buyerPassword(): string {
  const file = stateFile();
  const legacy = join(HARNESS_DIR, '.state.json');   // pre-fix location inside the Vite root
  for (const candidate of [file, legacy]) {
    try {
      const state = JSON.parse(readFileSync(candidate, 'utf8'));
      if (typeof state.buyerPassword === 'string') {
        if (candidate === legacy) {   // migrate: keep the existing demo accounts' password, remove the served copy
          mkdirSync(dirname(file), { recursive: true });
          writeFileSync(file, JSON.stringify({ buyerPassword: state.buyerPassword }, null, 2), { mode: 0o600 });
          rmSync(legacy, { force: true });
        }
        return state.buyerPassword;
      }
    } catch {
      // not there yet
    }
  }
  const buyerPassword = `Demo#${randomBytes(9).toString('base64url')}`;
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, JSON.stringify({ buyerPassword }, null, 2), { mode: 0o600 });
  return buyerPassword;
}
