// @vitest-environment node
import { relative, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { stateFile } from './env';

describe('demo harness secrets (I3)', () => {
  it('keeps the buyer-password state outside the Vite root, so the dev server can never serve it', () => {
    const rel = relative(resolve(__dirname, '..'), stateFile());
    expect(rel.startsWith('..')).toBe(true);
  });
});
