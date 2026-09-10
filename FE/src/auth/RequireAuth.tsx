import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { useAuth } from './AuthContext';

export default function RequireAuth({ children }: { children: ReactNode }) {
  const { session, logoutNotice } = useAuth();
  if (!session) {
    const state = logoutNotice ? { notice: logoutNotice.text, variant: logoutNotice.variant } : undefined;
    return <Navigate to="/login" replace state={state} />;
  }
  return <>{children}</>;
}
