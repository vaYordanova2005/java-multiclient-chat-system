import { useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { ApiError } from '../../api/client';
import { login } from '../../api/auth';
import { useAuth } from '../../auth/AuthContext';
import PasswordVisibilityToggle from './PasswordVisibilityToggle';
import styles from './AuthScreen.module.css';

type StatusVariant = 'heading' | 'error' | 'success';

export default function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation() as {
    state?: { username?: string; notice?: string; variant?: 'success' | 'error' };
  };
  const auth = useAuth();

  const [username, setUsername] = useState(location.state?.username ?? '');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [remember, setRemember] = useState(true);
  const [status, setStatus] = useState<{ text: string; variant: StatusVariant }>(
    location.state?.notice
      ? { text: location.state.notice, variant: location.state.variant ?? 'success' }
      : { text: 'Welcome Back!', variant: 'heading' },
  );
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    const user = username.trim();
    const pass = password;
    if (!user || !pass) {
      setStatus({ text: 'Please fill in all fields', variant: 'error' });
      return;
    }

    setSubmitting(true);
    setStatus({ text: 'Connecting...', variant: 'heading' });

    try {
      const result = await login(user, pass);
      auth.login({ token: result.token, username: result.username }, remember);
      navigate('/chat', { replace: true });
    } catch (err) {
      const message = err instanceof ApiError ? err.message : 'Could not reach the server';
      setStatus({ text: message, variant: 'error' });
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className={styles.authPage}>
      <form className={styles.authCard} onSubmit={handleSubmit}>
        <div className={styles.avatarCircle}>
          <svg width="40" height="40" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
            <circle cx="12" cy="8" r="4" />
            <path d="M4 20c0-4 3.5-7 8-7s8 3 8 7z" />
          </svg>
        </div>

        <div
          className={
            status.variant === 'error'
              ? styles.statusError
              : status.variant === 'success'
                ? styles.statusSuccess
                : styles.authTitle
          }
        >
          {status.text}
        </div>

        <div className={styles.fieldGroup}>
          <span className={styles.fieldIcon}>
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <circle cx="12" cy="8" r="4" />
              <path d="M4 20c0-4 3.5-7 8-7s8 3 8 7" />
            </svg>
          </span>
          <input
            className={styles.authField}
            placeholder="Username..."
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
          />
        </div>

        <div className={styles.fieldGroup}>
          <span className={styles.fieldIcon}>
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <rect x="5" y="11" width="14" height="9" rx="2" />
              <path d="M8 11V7a4 4 0 0 1 8 0v4" />
            </svg>
          </span>
          <input
            className={`${styles.authField} ${styles.authFieldToggleable}`}
            type={showPassword ? 'text' : 'password'}
            placeholder="Password..."
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
          />
          <PasswordVisibilityToggle visible={showPassword} onToggle={() => setShowPassword((v) => !v)} />
        </div>

        <div className={styles.rememberRow}>
          <label className={styles.rememberLabel}>
            <input type="checkbox" checked={remember} onChange={(e) => setRemember(e.target.checked)} />
            Remember me
          </label>
          <Link className={styles.forgotLink} to="/forgot-password" state={{ username }}>
            Forgot Password?
          </Link>
        </div>

        <button className={styles.authButton} type="submit" disabled={submitting}>
          Join Chat
        </button>

        <Link className={styles.authLink} to="/register" state={{ username }}>
          Don't have an account? Sign up
        </Link>
      </form>
    </div>
  );
}
