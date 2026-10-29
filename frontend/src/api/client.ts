import { API_BASE_URL } from '../config';
import type {
  DaySummary,
  DishAnalysis,
  Food,
  MealGroup,
  MealType,
  PeriodReport,
  PlanItem,
  PlanResult,
  ReplaceConstraintsInput,
  SubmitMealRequest,
  SubmitMealResult,
  UserSettings
} from './types';

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

export function shiftDate(date: string, days: number): string {
  const base = new Date(`${date}T00:00:00`);
  base.setDate(base.getDate() + days);
  return `${base.getFullYear()}-${pad(base.getMonth() + 1)}-${pad(base.getDate())}`;
}

export function weekStart(date: string): string {
  const base = new Date(`${date}T00:00:00`);
  const weekday = (base.getDay() + 6) % 7;
  return shiftDate(date, -weekday);
}

function pad(value: number): string {
  return value < 10 ? `0${value}` : `${value}`;
}

/** 生成本次操作的幂等键，重复提交不会重复计入。 */
export function newIdempotencyKey(): string {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

/** 当日该餐次已有分组则复用，否则新建一个。 */
export async function ensureGroupId(date: string, mealType: MealType): Promise<string> {
  const day = await api.getDay(date);
  const existing = day.groups
    .filter((item) => item.group.mealType === mealType)
    .sort((left, right) => left.group.instanceSeq - right.group.instanceSeq)[0];
  if (existing) {
    return existing.group.id;
  }
  const created = await api.createGroup(date, mealType);
  return created.id;
}

/** 按当前时间推断该记录哪一餐。 */
export function mealTypeForHour(hour: number): MealType {
  if (hour < 10) {
    return 'breakfast';
  }
  if (hour < 15) {
    return 'lunch';
  }
  if (hour < 21) {
    return 'dinner';
  }
  return 'snack';
}

export const api = {
  getDay(date: string = today()): Promise<DaySummary> {
    return request<DaySummary>(`/meal-groups?date=${encodeURIComponent(date)}`);
  },

  createGroup(date: string, mealType: MealType): Promise<MealGroup> {
    return request<MealGroup>('/meal-groups', {
      method: 'POST',
      body: JSON.stringify({ meal_date: date, meal_type: mealType })
    });
  },

  submitEntry(groupId: string, body: SubmitMealRequest): Promise<SubmitMealResult> {
    return request<SubmitMealResult>(`/meal-groups/${groupId}/entries`, {
      method: 'POST',
      body: JSON.stringify(body)
    });
  },

  listFoods(query: string): Promise<Food[]> {
    const suffix = query.trim() ? `?q=${encodeURIComponent(query.trim())}` : '';
    return request<Food[]>(`/foods${suffix}`);
  },

  parseDish(text: string): Promise<DishAnalysis> {
    return request<DishAnalysis>('/foods-parse', {
      method: 'POST',
      body: JSON.stringify({ text })
    });
  },

  getSettings(): Promise<UserSettings> {
    return request<UserSettings>('/settings');
  },

  saveSettings(
    body: Partial<Pick<UserSettings, 'dailyTargets' | 'preferences' | 'allergens' | 'avoid'>>
  ): Promise<{ ok: boolean }> {
    return request<{ ok: boolean }>('/settings', {
      method: 'PUT',
      body: JSON.stringify(body)
    });
  },

  getReport(periodType: 'weekly' | 'monthly', start: string): Promise<PeriodReport> {
    return request<PeriodReport>(
      `/reports/${periodType}?start=${encodeURIComponent(start)}`
    );
  },

  /** 让 AI 按当日缺口生成这一餐的菜单。 */
  createPlan(date: string, mealType: MealType): Promise<PlanResult> {
    return request<PlanResult>('/plan-sessions', {
      method: 'POST',
      body: JSON.stringify({ meal_date: date, meal_type: mealType })
    });
  },

  /** 在已有方案上提出调整（换菜、少油等）。 */
  revisePlan(
    sessionId: string,
    currentMenu: PlanItem[],
    constraints: ReplaceConstraintsInput = {}
  ): Promise<PlanResult> {
    return request<PlanResult>(`/plan-sessions/${sessionId}/messages`, {
      method: 'POST',
      body: JSON.stringify({ current_menu: currentMenu, constraints })
    });
  }
};
