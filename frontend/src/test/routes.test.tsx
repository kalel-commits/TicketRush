import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import { AuthProvider } from '../auth/AuthContext';

afterEach(() => { vi.restoreAllMocks(); sessionStorage.clear(); });

describe('routes', () => {
  it('/bookings (the assistant\'s "My bookings" link) opens My bookings', async () => {
    sessionStorage.setItem('ticketrush.token', 't');
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ content: [] }), { status: 200 }));
    render(<QueryClientProvider client={new QueryClient()}><AuthProvider>
      <MemoryRouter initialEntries={['/bookings']}><AppRoutes /></MemoryRouter></AuthProvider></QueryClientProvider>);
    expect(await screen.findByRole('heading', { name: 'My bookings' })).toBeInTheDocument();
  });

  it('a session that ends (401) sends the user to sign in', async () => {
    sessionStorage.setItem('ticketrush.token', 'expired');
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('', { status: 401 }));
    render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AuthProvider>
      <MemoryRouter initialEntries={['/me/bookings']}><AppRoutes /></MemoryRouter></AuthProvider></QueryClientProvider>);
    expect(await screen.findByRole('button', { name: 'Sign in' })).toBeInTheDocument();
    expect(sessionStorage.getItem('ticketrush.token')).toBeNull();
  });
});
