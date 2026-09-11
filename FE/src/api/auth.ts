import { postJson } from './client';

export interface LoginResponse {
  token: string;
  username: string;
}

export function login(username: string, password: string): Promise<LoginResponse> {
  return postJson<LoginResponse>('/api/auth/login', { username, password });
}

export function register(
  username: string,
  password: string,
  securityQuestion: string,
  securityAnswer: string,
): Promise<void> {
  return postJson<void>('/api/auth/register', {
    username,
    password,
    securityQuestion,
    securityAnswer,
  });
}

export interface ResetQuestionResponse {
  question: string;
}

export function getResetQuestion(username: string): Promise<ResetQuestionResponse> {
  return postJson<ResetQuestionResponse>('/api/auth/reset/question', { username });
}

export function submitResetAnswer(
  username: string,
  answer: string,
  newPassword: string,
): Promise<void> {
  return postJson<void>('/api/auth/reset/verify', { username, answer, newPassword });
}
