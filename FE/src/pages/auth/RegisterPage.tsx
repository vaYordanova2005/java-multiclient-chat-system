import { useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { ApiError } from '../../api/client';
import { register } from '../../api/auth';
import PasswordVisibilityToggle from './PasswordVisibilityToggle';
import styles from './AuthScreen.module.css';

export default function RegisterPage() {
  const navigate = useNavigate();
  const location = useLocation() as { state?: { username?: string } };

  const [username, setUsername] = useState(location.state?.username ?? '');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [repeatPassword, setRepeatPassword] = useState('');
  const [showRepeatPassword, setShowRepeatPassword] = useState(false);
  const [securityQuestion, setSecurityQuestion] = useState('');
  const [securityAnswer, setSecurityAnswer] = useState('');
  const [status, setStatus] = useState<{ text: string; isError: boolean }>({
    text: 'Create Account',
    isError: false,
  });
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    const user = username.trim();
    const pass = password;
    const rep = repeatPassword;
    const question = securityQuestion.trim();
    const answer = securityAnswer.trim();

    if (!user || !pass || !rep || !question || !answer) {
      setStatus({ text: 'Please fill in all fields', isError: true });
      return;
    }
    if (pass !== rep) {
      setStatus({ text: 'Passwords do not match', isError: true });
      return;
    }
    if (pass.length < 6) {
      setStatus({ text: 'Password must be at least 6 characters', isError: true });
      return;
    }

    setSubmitting(true);
    setStatus({ text: 'Creating account...', isError: false });

    try {
      await register(user, pass, question, answer);
      navigate('/login', {
        replace: true,
        state: { username: user, notice: 'Registration successful! Please login.' },
      });
    } catch (err) {
      const message = err instanceof ApiError ? err.message : 'Could not reach the server';
      setStatus({ text: message, isError: true });
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

        <div className={status.isError ? styles.statusError : styles.authTitle}>{status.text}</div>

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
            autoComplete="new-password"
          />
          <PasswordVisibilityToggle visible={showPassword} onToggle={() => setShowPassword((v) => !v)} />
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
            type={showRepeatPassword ? 'text' : 'password'}
            placeholder="Repeat Password..."
            value={repeatPassword}
            onChange={(e) => setRepeatPassword(e.target.value)}
            autoComplete="new-password"
          />
          <PasswordVisibilityToggle visible={showRepeatPassword} onToggle={() => setShowRepeatPassword((v) => !v)} />
        </div>

        <div className={styles.fieldGroup}>
          <span className={styles.fieldIcon}>
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <circle cx="12" cy="12" r="9" />
              <path d="M9.1 9a3 3 0 0 1 5.8 1c0 2-3 2-3 4" />
              <line x1="12" y1="17" x2="12.01" y2="17" />
            </svg>
          </span>
          <input
            className={styles.authField}
            placeholder="Security question (e.g. 'Name of first pet?')..."
            value={securityQuestion}
            onChange={(e) => setSecurityQuestion(e.target.value)}
          />
        </div>

        <div className={styles.fieldGroup}>
          <span className={styles.fieldIcon}>
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <path d="M12 20h9" />
              <path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z" />
            </svg>
          </span>
          <input
            className={styles.authField}
            placeholder="Answer..."
            value={securityAnswer}
            onChange={(e) => setSecurityAnswer(e.target.value)}
          />
        </div>

        <button className={styles.authButton} type="submit" disabled={submitting}>
          Register
        </button>

        <Link className={styles.authLink} to="/login" state={{ username }}>
          Already have an account? Sign in
        </Link>
      </form>
    </div>
  );
}
