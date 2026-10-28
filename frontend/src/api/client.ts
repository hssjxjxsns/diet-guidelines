import { API_BASE_URL } from '../config';
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
  let response: Response;
  try {
    response = await fetch(`${API_BASE_URL}${path}`, {
      ...init,
      headers: {
        'Content-Type': 'application/json',
        ...init?.headers
      }
    });
  } catch {
    throw new ApiError(0, 'NETWORK_ERROR', `无法连接后端 ${API_BASE_URL}，请确认服务已启动`);
  }

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
