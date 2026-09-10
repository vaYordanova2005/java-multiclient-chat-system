import { useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { ApiError } from '../../api/client';
import { register } from '../../api/auth';
import styles from './AuthScreen.module.css';

export default function RegisterPage() {
  const navigate = useNavigate();
  const location = useLocation() as { state?: { username?: string } };

  const [username, setUsername] = useState(location.state?.username ?? '');
  const [password, setPassword] = useState('');
  const [repeatPassword, setRepeatPassword] = useState('');
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
      setStatus({ text: '❌ Please fill in all fields', isError: true });
      return;
    }
    if (pass !== rep) {
      setStatus({ text: '❌ Passwords do not match', isError: true });
      return;
    }
    if (pass.length < 6) {
      setStatus({ text: '❌ Password must be at least 6 characters', isError: true });
      return;
    }

    setSubmitting(true);
    setStatus({ text: 'Creating account...', isError: false });

    try {
      await register(user, pass, question, answer);
      navigate('/login', {
        replace: true,
        state: { username: user, notice: '✅ Registration successful! Please login.' },
      });
    } catch (err) {
      const message = err instanceof ApiError ? err.message : 'Could not reach the server';
      setStatus({ text: `❌ ${message}`, isError: true });
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className={styles.page}>
      <form className={styles.card} onSubmit={handleSubmit}>
        <div className={status.isError ? styles.statusError : styles.title}>{status.text}</div>

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
          autoComplete="new-password"
        />
        <input
          className={styles.field}
          type="password"
          placeholder="Repeat Password..."
          value={repeatPassword}
          onChange={(e) => setRepeatPassword(e.target.value)}
          autoComplete="new-password"
        />
        <input
          className={styles.field}
          placeholder="Security question (e.g. 'Name of first pet?')..."
          value={securityQuestion}
          onChange={(e) => setSecurityQuestion(e.target.value)}
        />
        <input
          className={styles.field}
          placeholder="Answer..."
          value={securityAnswer}
          onChange={(e) => setSecurityAnswer(e.target.value)}
        />

        <button className={styles.primaryButton} type="submit" disabled={submitting}>
          Register
        </button>

        <Link className={styles.linkText} to="/login" state={{ username }}>
          Already have an account? Sign in
        </Link>
      </form>
    </div>
  );
}
