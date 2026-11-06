import { Compass, Info, Lightning, List, MagnifyingGlass, SignIn, SignOut, Ticket, UserCircle, X } from '@phosphor-icons/react';
import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { Link, NavLink, Outlet, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

/** Brand mark: a ticket stub with a lightning cut — drawn inline, no image file. */
export function BrandMark({ size = 30 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" aria-hidden>
      <defs>
        <linearGradient id="tr-brand" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="var(--accent)" />
          <stop offset="1" stopColor="var(--accent-2)" />
        </linearGradient>
      </defs>
      <path d="M5 7h22a2 2 0 0 1 2 2v3.2a3.8 3.8 0 0 0 0 7.6V23a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-3.2a3.8 3.8 0 0 0 0-7.6V9a2 2 0 0 1 2-2Z" fill="url(#tr-brand)" />
      <path d="M17.5 10.5 12.5 17h3.6l-1.6 4.5 5-6.5h-3.6l1.6-4.5Z" fill="var(--on-accent)" />
    </svg>
  );
}

function Item({ to, icon, children, onClick }: { to: string; icon: ReactNode; children: ReactNode; onClick?: () => void }) {
  return (
    <NavLink
      to={to}
      onClick={onClick}
      className={({ isActive }) =>
        `inline-flex min-h-11 items-center gap-2 rounded-full px-4 text-[15px] transition-colors duration-150 ${
          isActive ? 'bg-muted font-semibold text-fg' : 'text-muted-fg hover:bg-muted hover:text-fg'}`}
    >
      {icon}
      {children}
    </NavLink>
  );
}

function NavSearch({ id, onDone }: { id: string; onDone?: () => void }) {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [q, setQ] = useState(params.get('q') ?? '');
  useEffect(() => { setQ(params.get('q') ?? ''); }, [params]);
  function submit(e: FormEvent) {
    e.preventDefault();
    const next = new URLSearchParams();
    if (q.trim()) next.set('q', q.trim());
    navigate({ pathname: '/events', search: next.toString() ? `?${next}` : '', hash: 'browse' });
    onDone?.();
  }
  return (
    <form role="search" onSubmit={submit} className="relative w-full">
      <label htmlFor={id} className="sr-only">Search events, artists, venues or cities</label>
      <MagnifyingGlass aria-hidden size={18} className="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-muted-fg" />
      <input
        id={id} type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search artists, events, cities"
        className="min-h-11 w-full rounded-full border border-input-border bg-surface pl-10 pr-4 text-[15px] text-fg placeholder:text-muted-fg"
      />
    </form>
  );
}

function AccountMenu() {
  const { email, logout } = useAuth();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => { if (!ref.current?.contains(e.target as Node)) setOpen(false); };
    const esc = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    document.addEventListener('mousedown', close);
    document.addEventListener('keydown', esc);
    return () => { document.removeEventListener('mousedown', close); document.removeEventListener('keydown', esc); };
  }, [open]);
  const initial = (email ?? '?').charAt(0).toUpperCase();
  return (
    <div ref={ref} className="relative">
      <button
        type="button" aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen((o) => !o)}
        className="inline-flex min-h-11 cursor-pointer items-center gap-2 rounded-full border border-border bg-surface py-1 pl-1 pr-3 hover:bg-muted"
      >
        <span aria-hidden className="bg-brand grid h-8 w-8 place-items-center rounded-full text-sm font-bold text-on-accent">{initial}</span>
        <span className="max-w-36 truncate text-sm text-muted-fg">{email ?? 'Account'}</span>
      </button>
      {open && (
        <div role="menu" className="absolute right-0 top-full z-50 mt-2 w-60 rounded-2xl border border-border bg-card p-2 shadow-2xl shadow-black/50">
          <p className="truncate px-3 py-2 text-sm text-muted-fg">Signed in as<br /><span className="text-fg">{email ?? 'your account'}</span></p>
          <Link role="menuitem" to="/me/bookings" onClick={() => setOpen(false)} className="flex min-h-11 items-center gap-2 rounded-xl px-3 hover:bg-muted">
            <Ticket aria-hidden size={18} /> My bookings
          </Link>
          <button
            role="menuitem" type="button"
            onClick={() => { setOpen(false); logout(); navigate('/login'); }}
            className="flex min-h-11 w-full cursor-pointer items-center gap-2 rounded-xl px-3 text-left hover:bg-muted"
          >
            <SignOut aria-hidden size={18} /> Sign out
          </button>
        </div>
      )}
    </div>
  );
}

/** App chrome: demo notice, sticky nav (desktop + mobile menu), one main region, footer. */
export function Layout({ aside }: { aside?: ReactNode }) {
  const { token, email, logout } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [menu, setMenu] = useState(false);
  useEffect(() => { setMenu(false); }, [location.pathname]);
  // a new page starts at the top (React Router keeps the old scroll position); in-page #hash targets scroll themselves
  useEffect(() => { if (!location.hash) window.scrollTo({ top: 0, behavior: 'instant' as ScrollBehavior }); }, [location.pathname, location.hash]);
  const close = () => setMenu(false);

  return (
    <div className="flex min-h-dvh flex-col">
      <a href="#main" className="sr-only focus:not-sr-only focus:absolute focus:left-2 focus:top-2 focus:z-[60] focus:rounded focus:bg-card focus:p-2">
        Skip to main content
      </a>
      <div className="border-b border-border bg-surface">
        <p className="mx-auto flex max-w-7xl items-center justify-center gap-2 px-4 py-1.5 text-center text-xs text-muted-fg">
          <Info aria-hidden size={14} className="shrink-0" />
          Demo environment — all listings are fictional. No real tickets are sold and no real payments are taken.
        </p>
      </div>
      <header className="sticky top-0 z-40 border-b border-border bg-[color-mix(in_srgb,var(--bg)_82%,transparent)] backdrop-blur-xl">
        <nav aria-label="Main" className="mx-auto flex max-w-7xl items-center gap-2 px-4 py-2.5">
          <Link to="/events" className="mr-2 inline-flex min-h-11 items-center gap-2 rounded-full pr-2" aria-label="TicketRush home">
            <BrandMark />
            <span className="text-lg font-bold tracking-tight">Ticket<span className="text-gradient">Rush</span></span>
          </Link>
          <div className="hidden items-center gap-1 lg:flex">
            <Item to="/events" icon={<Compass aria-hidden size={20} />}>Discover events</Item>
            {token && <Item to="/me/bookings" icon={<Ticket aria-hidden size={20} />}>My bookings</Item>}
            {/* the engineering demo harness exists only on the dev server */}
            {import.meta.env.DEV && <Item to="/demo/flash-sale" icon={<Lightning aria-hidden size={20} />}>Live demo</Item>}
          </div>
          <div className="mx-2 hidden max-w-sm flex-1 md:block"><NavSearch id="nav-search" /></div>
          <span className="flex-1 md:hidden" />
          <div className="hidden lg:block">
            {token ? <AccountMenu /> : (
              <Link to="/login" className="bg-brand inline-flex min-h-11 items-center gap-2 rounded-full px-5 font-semibold text-on-accent hover:brightness-110">
                <SignIn aria-hidden size={18} /> Sign in
              </Link>
            )}
          </div>
          <button
            type="button" aria-expanded={menu} aria-controls="mobile-menu" onClick={() => setMenu((m) => !m)}
            className="inline-flex h-11 w-11 cursor-pointer items-center justify-center rounded-full border border-border bg-surface lg:hidden"
          >
            {menu ? <X aria-hidden size={20} /> : <List aria-hidden size={20} />}
            <span className="sr-only">{menu ? 'Close menu' : 'Open menu'}</span>
          </button>
        </nav>
        {menu && (
          <div id="mobile-menu" className="border-t border-border px-4 pb-4 pt-3 lg:hidden">
            <div className="mb-3 md:hidden"><NavSearch id="nav-search-mobile" onDone={close} /></div>
            <div className="flex flex-col gap-1">
              <Item to="/events" icon={<Compass aria-hidden size={20} />} onClick={close}>Discover events</Item>
              {token && <Item to="/me/bookings" icon={<Ticket aria-hidden size={20} />} onClick={close}>My bookings</Item>}
              {import.meta.env.DEV && <Item to="/demo/flash-sale" icon={<Lightning aria-hidden size={20} />} onClick={close}>Live demo</Item>}
              {token ? (
                <button
                  type="button" onClick={() => { close(); logout(); navigate('/login'); }}
                  className="inline-flex min-h-11 cursor-pointer items-center gap-2 rounded-full px-4 text-muted-fg hover:bg-muted hover:text-fg"
                >
                  <SignOut aria-hidden size={20} /> Sign out
                </button>
              ) : <Item to="/login" icon={<SignIn aria-hidden size={20} />} onClick={close}>Sign in</Item>}
              {token && <p className="flex items-center gap-2 px-4 pt-2 text-sm text-muted-fg"><UserCircle aria-hidden size={18} /> <span className="truncate">{email ?? 'Signed in'}</span></p>}
            </div>
          </div>
        )}
      </header>
      <main id="main" tabIndex={-1} className="mx-auto w-full max-w-7xl flex-1 px-4 py-6">
        <Outlet />
      </main>
      <footer className="border-t border-border bg-surface">
        <div className="mx-auto flex max-w-7xl flex-col gap-3 px-4 py-8 text-sm text-muted-fg md:flex-row md:items-center md:justify-between">
          <div className="flex items-center gap-2 text-fg"><BrandMark size={22} /><span className="font-semibold">TicketRush</span></div>
          <p className="max-w-2xl">
            A portfolio demo of a flash-sale ticketing platform. Artist names are used for illustration only; events, dates,
            venues and prices are fictional and no affiliation is implied.
          </p>
        </div>
      </footer>
      {aside}
    </div>
  );
}
