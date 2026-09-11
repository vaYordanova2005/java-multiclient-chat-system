import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ApiError } from '../../api/client';
import { getResetQuestion, submitResetAnswer } from '../../api/auth';
import styles from './AuthScreen.module.css';

export default function ForgotPasswordPage() {
  const navigate = useNavigate();

  const [step, setStep] = useState<1 | 2>(1);
  const [username, setUsername] = useState('');
  const [question, setQuestion] = useState('');
  const [answer, setAnswer] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [newPasswordRepeat, setNewPasswordRepeat] = useState('');
  const [status, setStatus] = useState({ text: '', isError: false });
  const [submitting, setSubmitting] = useState(false);

  async function handleStep1(e: FormEvent) {
    e.preventDefault();
    const user = username.trim();
    if (!user) {
      setStatus({ text: 'Enter your username', isError: true });
      return;
    }

    setSubmitting(true);
    setStatus({ text: 'Looking up...', isError: false });

    try {
      const result = await getResetQuestion(user);
      setQuestion(result.question);
      setStatus({ text: '', isError: false });
      setStep(2);
    } catch (err) {
      const message = err instanceof ApiError ? err.message : 'Could not reach the server';
      setStatus({ text: message, isError: true });
    } finally {
      setSubmitting(false);
    }
  }

  async function handleStep2(e: FormEvent) {
    e.preventDefault();
    const trimmedAnswer = answer.trim();
    const pass = newPassword;
    const passRepeat = newPasswordRepeat;

    if (!trimmedAnswer || !pass) {
      setStatus({ text: 'Fill in all fields', isError: true });
      return;
    }
    if (pass !== passRepeat) {
      setStatus({ text: 'Passwords do not match', isError: true });
      return;
    }
    if (pass.length < 6) {
      setStatus({ text: 'Password must be at least 6 characters', isError: true });
      return;
    }

    setSubmitting(true);
    setStatus({ text: 'Resetting...', isError: false });

    try {
      await submitResetAnswer(username.trim(), trimmedAnswer, pass);
      navigate('/login', {
        replace: true,
        state: { username: username.trim(), notice: 'Password reset! Please login.' },
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
      <div className={styles.authCard}>
        <div className={styles.avatarCircle}>
          <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <rect x="5" y="11" width="14" height="9" rx="2" />
            <path d="M8 11V7a4 4 0 0 1 8 0v4" />
          </svg>
        </div>

        <div className={styles.authTitle}>Reset Password</div>
        {status.text && (
          <div className={status.isError ? styles.statusError : styles.authStatusMuted}>{status.text}</div>
        )}

        {step === 1 && (
          <form className={styles.stepBox} onSubmit={handleStep1}>
            <div className={styles.fieldGroup}>
              <span className={styles.fieldIcon}>
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                  <circle cx="12" cy="8" r="4" />
                  <path d="M4 20c0-4 3.5-7 8-7s8 3 8 7" />
                </svg>
              </span>
              <input
                className={styles.authField}
                placeholder="Your username..."
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                autoComplete="username"
              />
            </div>
            <button className={styles.authButton} type="submit" disabled={submitting}>
              Continue
            </button>
          </form>
        )}

        {step === 2 && (
          <form className={styles.stepBox} onSubmit={handleStep2}>
            <div className={styles.authQuestionLabel}>{question}</div>
            <div className={styles.fieldGroup}>
              <span className={styles.fieldIcon}>
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                  <path d="M12 20h9" />
                  <path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z" />
                </svg>
              </span>
              <input
                className={styles.authField}
                placeholder="Your answer..."
                value={answer}
                onChange={(e) => setAnswer(e.target.value)}
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
                className={styles.authField}
                type="password"
                placeholder="New password..."
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
                autoComplete="new-password"
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
                className={styles.authField}
                type="password"
                placeholder="Repeat new password..."
                value={newPasswordRepeat}
                onChange={(e) => setNewPasswordRepeat(e.target.value)}
                autoComplete="new-password"
              />
            </div>
            <button className={styles.authButton} type="submit" disabled={submitting}>
              Reset Password
            </button>
          </form>
        )}

        <Link className={styles.authMutedLink} to="/login">
          ← Back to login
        </Link>
      </div>
    </div>
  );
}
