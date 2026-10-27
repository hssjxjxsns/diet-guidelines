import { API_BASE_URL } from '../config';
import { supabase } from '../lib/supabase';
import type { DaySummary, SubmitMealRequest, SubmitMealResult } from './types';

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const { data } = await supabase.auth.getSession();
  const token = data.session?.access_token;
  if (!token) throw new ApiError(401, 'UNAUTHORIZED', '请先登录');

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
      ...init?.headers
    }
  });

  const payload: unknown = await response.json().catch(() => null);

  if (!response.ok) {
    const failure = payload as { code?: string; message?: string } | null;
    throw new ApiError(
      response.status,
      failure?.code ?? 'REQUEST_FAILED',
      failure?.message ?? `请求失败 (${response.status})`
    );
  }

  return payload as T;
}

/** 用户时区自然日，格式 YYYY-MM-DD。 */
export function today(): string {
  const now = new Date();
  const offset = now.getTimezoneOffset() * 60_000;
  return new Date(now.getTime() - offset).toISOString().slice(0, 10);
}

export const api = {
  getDay(date: string = today()): Promise<DaySummary> {
    return request<DaySummary>(`/meal-groups?date=${encodeURIComponent(date)}`);
  },

  submitEntry(groupId: string, body: SubmitMealRequest): Promise<SubmitMealResult> {
    return request<SubmitMealResult>(`/meal-groups/${groupId}/entries`, {
      method: 'POST',
      body: JSON.stringify(body)
    });
  }
};
