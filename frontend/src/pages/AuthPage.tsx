import { useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { Button, ErrorNotice } from '../components/ui';
import { BrandMark } from '../components/Layout';
import { EventArt } from '../catalog/EventArt';

/** Login and register (spec §23.2): visible labels, autocomplete, paste allowed, errors on blur. */
export function AuthPage({ mode }: { mode: 'login' | 'register' }) {
  const { login, register } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [touched, setTouched] = useState<{ email?: boolean; password?: boolean }>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const emailError = touched.email && !/^\S+@\S+\.\S+$/.test(email) ? 'Enter an email address like name@example.com.' : '';
  const passwordError = touched.password && password.length < 8 ? 'Use at least 8 characters.' : '';

  async function submit(e: FormEvent) {
    e.preventDefault();
    setTouched({ email: true, password: true });
    if (!/^\S+@\S+\.\S+$/.test(email) || password.length < 8) return;
    setBusy(true);
    setError(null);
    try {
      await (mode === 'login' ? login(email, password) : register(email, password));
      const from = (location.state as { from?: string } | null)?.from ?? '/events';
      navigate(from, { replace: true });
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  const title = mode === 'login' ? 'Sign in' : 'Create an account';
  return (
    <div className="mx-auto grid max-w-5xl overflow-hidden rounded-3xl border border-border bg-card lg:grid-cols-2">
      <div className="relative isolate hidden min-h-[560px] flex-col justify-end gap-3 p-10 lg:flex">
        <EventArt seed="ticketrush-auth" category="POP" className="absolute inset-0 -z-10 h-full w-full" />
        <div aria-hidden className="absolute inset-0 -z-10 bg-gradient-to-t from-[#05060d] via-[#05060d99] to-transparent" />
        <BrandMark size={44} />
        <p className="text-3xl font-bold leading-tight tracking-tight">Front-row seats,<br /><span className="text-gradient">without the rush.</span></p>
        <p className="max-w-sm text-[#d6dcea]">Discover concerts and festivals, pick your exact seats and check out in seconds — even when everyone else is trying too.</p>
      </div>
      <div className="flex flex-col justify-center p-6 sm:p-10">
      <h1 className="mb-1 text-3xl font-bold tracking-tight">{title}</h1>
      <p className="mb-6 text-muted-fg">{mode === 'login' ? 'Welcome back. Sign in to book tickets.' : 'It takes a few seconds. Demo accounts only.'}</p>
      <div>
        <form onSubmit={submit} noValidate className="flex flex-col gap-4">
          <div className="flex flex-col gap-1">
            <label htmlFor="email" className="font-medium">Email</label>
            <input
              id="email" type="email" autoComplete="email" value={email}
              onChange={(e) => setEmail(e.target.value)} onBlur={() => setTouched((t) => ({ ...t, email: true }))}
              aria-invalid={!!emailError} aria-describedby={emailError ? 'email-error' : undefined}
              className="min-h-12 rounded-xl border border-input-border bg-surface px-4 text-fg"
            />
            {emailError && <p id="email-error" className="text-sm text-danger">{emailError}</p>}
          </div>
          <div className="flex flex-col gap-1">
            <label htmlFor="password" className="font-medium">Password</label>
            <input
              id="password" type="password" autoComplete={mode === 'login' ? 'current-password' : 'new-password'} value={password}
              onChange={(e) => setPassword(e.target.value)} onBlur={() => setTouched((t) => ({ ...t, password: true }))}
              aria-invalid={!!passwordError} aria-describedby={passwordError ? 'password-error' : undefined}
              className="min-h-12 rounded-xl border border-input-border bg-surface px-4 text-fg"
            />
            {passwordError && <p id="password-error" className="text-sm text-danger">{passwordError}</p>}
          </div>
          <ErrorNotice error={error} />
          <Button type="submit" busy={busy} className="mt-1 min-h-12">{title}</Button>
        </form>
      </div>
      <p className="mt-6 text-muted-fg">
        {mode === 'login' ? <>New here? <Link className="font-medium text-accent underline-offset-4 hover:underline" to="/register">Create an account</Link></>
          : <>Already registered? <Link className="font-medium text-accent underline-offset-4 hover:underline" to="/login">Sign in</Link></>}
      </p>
      </div>
    </div>
  );
}
