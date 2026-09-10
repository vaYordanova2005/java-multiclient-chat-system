import { useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { ApiError } from '../../api/client';
import { login } from '../../api/auth';
import { useAuth } from '../../auth/AuthContext';
import styles from './AuthScreen.module.css';

type StatusVariant = 'heading' | 'error' | 'success';

export default function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation() as { state?: { username?: string; notice?: string } };
  const auth = useAuth();

  const [username, setUsername] = useState(location.state?.username ?? '');
  const [password, setPassword] = useState('');
  const [status, setStatus] = useState<{ text: string; variant: StatusVariant }>(
    location.state?.notice
      ? { text: location.state.notice, variant: 'success' }
      : { text: 'Welcome Back!', variant: 'heading' },
  );
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    const user = username.trim();
    const pass = password.trim();
    if (!user || !pass) {
      setStatus({ text: '❌ Please fill in all fields', variant: 'error' });
      return;
    }

    setSubmitting(true);
    setStatus({ text: 'Connecting...', variant: 'heading' });

    try {
      const result = await login(user, pass);
      auth.login({ token: result.token, username: result.username });
      navigate('/chat', { replace: true });
    } catch (err) {
      const message = err instanceof ApiError ? err.message : 'Could not reach the server';
      setStatus({ text: `❌ ${message}`, variant: 'error' });
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className={styles.page}>
      <form className={styles.card} onSubmit={handleSubmit}>
        <div
          className={
            status.variant === 'error'
              ? styles.statusError
              : status.variant === 'success'
                ? styles.statusSuccess
                : styles.title
          }
        >
          {status.text}
        </div>

        <input
          className={styles.field}
          placeholder="Username..."
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          autoComplete="username"
        />
        <input
          className={styles.field}
          type="password"
          placeholder="Password..."
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          autoComplete="current-password"
        />

        <button className={styles.primaryButton} type="submit" disabled={submitting}>
          Join Chat
        </button>

        <Link className={styles.linkText} to="/register" state={{ username }}>
          Don't have an account? Sign up
        </Link>
        <Link className={styles.mutedLinkText} to="/forgot-password" state={{ username }}>
          Forgot password?
        </Link>
      </form>
    </div>
  );
}
