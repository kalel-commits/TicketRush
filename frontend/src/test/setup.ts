import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

// RTL only auto-cleans with vitest globals enabled; without it, earlier tests' DOM leaks into later ones.
afterEach(() => cleanup());
