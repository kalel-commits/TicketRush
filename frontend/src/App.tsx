import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { BrowserRouter, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { AuthProvider, useAuth } from './auth/AuthContext';
import { Layout } from './components/Layout';
import { AssistantPanel } from './assistant/AssistantPanel';
import { AuthPage } from './pages/AuthPage';
import { EventsPage } from './pages/EventsPage';
import { SeatMapPage } from './pages/SeatMapPage';
import { BookingPage } from './pages/BookingPage';
import { MyBookingsPage } from './pages/MyBookingsPage';
import { FlashSaleDemoPage } from './pages/FlashSaleDemoPage';

export const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: false } },
});

function RequireAuth({ children }: { children: ReactNode }) {
  const { token } = useAuth();
  const location = useLocation();
  return token ? <>{children}</> : <Navigate to="/login" replace state={{ from: location.pathname }} />;
}

/** The assistant needs a signed-in user (its API requires a token). */
function SignedIn({ children }: { children: ReactNode }) {
  return useAuth().token ? <>{children}</> : null;
}

export function AppRoutes() {
  return (
    <Routes>
      <Route element={<Layout aside={<SignedIn><AssistantPanel /></SignedIn>} />}>
        <Route path="/login" element={<AuthPage mode="login" />} />
        <Route path="/register" element={<AuthPage mode="register" />} />
        <Route path="/events" element={<RequireAuth><EventsPage /></RequireAuth>} />
        <Route path="/events/:id" element={<RequireAuth><SeatMapPage /></RequireAuth>} />
        <Route path="/bookings/:id" element={<RequireAuth><BookingPage /></RequireAuth>} />
        <Route path="/me/bookings" element={<RequireAuth><MyBookingsPage /></RequireAuth>} />
        {/* the assistant's suggested action (SuggestedActions.java) links to /bookings */}
        <Route path="/bookings" element={<Navigate to="/me/bookings" replace />} />
        {/* engineering demo (plan 9): uses its own demo-buyer sessions from the dev harness, not the visitor's login */}
        {import.meta.env.DEV && <Route path="/demo/flash-sale" element={<FlashSaleDemoPage />} />}
        <Route path="*" element={<RequireAuth><Navigate to="/events" replace /></RequireAuth>} />
      </Route>
    </Routes>
  );
}

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <BrowserRouter>
          <AppRoutes />
        </BrowserRouter>
      </AuthProvider>
    </QueryClientProvider>
  );
}

export { RequireAuth };
