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
  const [status, setStatus] = useState('');
  const [submitting, setSubmitting] = useState(false);

  async function handleStep1(e: FormEvent) {
    e.preventDefault();
    const user = username.trim();
    if (!user) {
      setStatus('❌ Enter your username');
      return;
    }

    setSubmitting(true);
    setStatus('Looking up...');

    try {
      const result = await getResetQuestion(user);
      setQuestion(result.question);
      setStatus('');
      setStep(2);
    } catch (err) {
      const message = err instanceof ApiError ? err.message : 'Could not reach the server';
      setStatus(`❌ ${message}`);
    } finally {
      setSubmitting(false);
    }
  }

  async function handleStep2(e: FormEvent) {
    e.preventDefault();
    const trimmedAnswer = answer.trim();
    const pass = newPassword.trim();
    const passRepeat = newPasswordRepeat.trim();

    if (!trimmedAnswer || !pass) {
      setStatus('❌ Fill in all fields');
      return;
    }
    if (pass !== passRepeat) {
      setStatus('❌ Passwords do not match');
      return;
    }
    if (pass.length < 6) {
      setStatus('❌ Password must be at least 6 characters');
      return;
    }

    setSubmitting(true);
    setStatus('Resetting...');

    try {
      await submitResetAnswer(username.trim(), trimmedAnswer, pass);
      navigate('/login', {
        replace: true,
        state: { username: username.trim(), notice: '✅ Password reset! Please login.' },
      });
    } catch (err) {
      const message = err instanceof ApiError ? err.message : 'Could not reach the server';
      setStatus(`❌ ${message}`);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className={styles.page}>
      <div className={styles.card}>
        <div className={styles.title}>Reset Password</div>
        {status && <div className={styles.statusMuted}>{status}</div>}

        {step === 1 && (
          <form className={styles.stepBox} onSubmit={handleStep1}>
            <input
              className={styles.field}
              placeholder="Your username..."
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoComplete="username"
            />
            <button className={styles.primaryButton} type="submit" disabled={submitting}>
              Continue
            </button>
          </form>
        )}

        {step === 2 && (
          <form className={styles.stepBox} onSubmit={handleStep2}>
            <div className={styles.questionLabel}>{question}</div>
            <input
              className={styles.field}
              placeholder="Your answer..."
              value={answer}
              onChange={(e) => setAnswer(e.target.value)}
            />
            <input
              className={styles.field}
              type="password"
              placeholder="New password..."
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
              autoComplete="new-password"
            />
            <input
              className={styles.field}
              type="password"
              placeholder="Repeat new password..."
              value={newPasswordRepeat}
              onChange={(e) => setNewPasswordRepeat(e.target.value)}
              autoComplete="new-password"
            />
            <button className={styles.primaryButton} type="submit" disabled={submitting}>
              Reset Password
            </button>
          </form>
        )}

        <Link className={styles.mutedLinkText} to="/login">
          ← Back to login
        </Link>
      </div>
    </div>
  );
}
