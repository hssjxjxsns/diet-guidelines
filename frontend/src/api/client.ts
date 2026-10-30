import { API_BASE_URL } from '../config';
import type {
  AcceptPlanResult,
  DaySummary,
  Food,
  MealDraftItem,
  MealGroup,
  MealType,
  ParseResponse,
  PeriodReport,
  PlanItem,
  PlanResult,
  ReplaceConstraintsInput,
  SubmitMealResult,
  UserSettings
} from './types';

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly details?: unknown
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

interface FailurePayload {
  code?: string;
  message?: string;
  details?: unknown;
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
    const failure = payload as FailurePayload | null;
    throw new ApiError(
      response.status,
      failure?.code ?? 'REQUEST_FAILED',
      failure?.message ?? `请求失败 (${response.status})`,
      failure?.details
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

/** 按当前时间推断该记录哪一餐。 */
export function mealTypeForHour(hour: number): MealType {
  if (hour < 10) {
    return 'breakfast';
  }
  if (hour < 15) {
    return 'lunch';
  }
  if (hour < 17) {
    return 'second_main';
  }
  if (hour < 21) {
    return 'dinner';
  }
  if (hour < 23) {
    return 'snack';
  }
  return 'night_snack';
}

export const MEAL_LABELS: Record<MealType, string> = {
  breakfast: '早餐',
  lunch: '午餐',
  second_main: '第二顿',
  dinner: '晚餐',
  snack: '加餐',
  night_snack: '夜宵'
};

export const MEAL_ORDER: MealType[] = ['breakfast', 'lunch', 'second_main', 'dinner', 'snack', 'night_snack'];

export const api = {
  getDay(date: string = today()): Promise<DaySummary> {
    return request<DaySummary>(`/meal-groups?date=${encodeURIComponent(date)}`);
  },

  getGroup(groupId: string): Promise<{ group: MealGroup; draftItems: MealDraftItem[] }> {
    return request<{ group: MealGroup; draftItems: MealDraftItem[] }>(`/meal-groups/${groupId}`);
  },

  /** 开餐：惰性建组（已有草稿复用，已记录组重开为草稿保留版本链）。 */
  openDraft(date: string, mealType: MealType, instanceSeq?: number): Promise<MealGroup> {
    return request<MealGroup>('/meal-groups/drafts', {
      method: 'POST',
      body: JSON.stringify({
        meal_date: date,
        meal_type: mealType,
        ...(instanceSeq !== undefined ? { instance_seq: instanceSeq } : {})
      })
    });
  },

  addDraftItem(
    groupId: string,
    body: {
      foodId?: string;
      recipeId?: string;
      rawName: string;
      amountG: number;
      valueType?: string;
      aiSource?: string;
      confirmed?: boolean;
      draft_revision?: number;
    }
  ): Promise<MealDraftItem> {
    return request<MealDraftItem>(`/meal-groups/${groupId}/draft/items`, {
      method: 'POST',
      body: JSON.stringify(body)
    });
  },

  updateDraftItem(
    groupId: string,
    itemId: string,
    body: {
      rawName?: string;
      amountG?: number;
      confirmationStatus?: 'confirmed' | 'rejected';
      foodId?: string;
      draft_revision?: number;
    }
  ): Promise<MealDraftItem> {
    return request<MealDraftItem>(`/meal-groups/${groupId}/draft/items/${itemId}`, {
      method: 'PATCH',
      body: JSON.stringify(body)
    });
  },

  removeDraftItem(groupId: string, itemId: string, draftRevision?: number): Promise<{ ok: boolean }> {
    const suffix = draftRevision !== undefined ? `?draft_revision=${draftRevision}` : '';
    return request<{ ok: boolean }>(`/meal-groups/${groupId}/draft/items/${itemId}${suffix}`, {
      method: 'DELETE'
    });
  },

  copyPrevious(groupId: string, draftRevision?: number): Promise<{ items: MealDraftItem[] }> {
    return request<{ items: MealDraftItem[] }>(`/meal-groups/${groupId}/draft/copy-previous`, {
      method: 'POST',
      body: JSON.stringify(draftRevision !== undefined ? { draft_revision: draftRevision } : {})
    });
  },

  /** commit：草稿 → 正式记录（规则校验硬拦 + 事务 + 幂等 + 聚合 + 报表 stale）。 */
  commitDraft(groupId: string, draftRevision: number | undefined, idempotencyKey: string): Promise<SubmitMealResult> {
    return request<SubmitMealResult>(`/meal-groups/${groupId}/draft/commit`, {
      method: 'POST',
      body: JSON.stringify({
        ...(draftRevision !== undefined ? { draft_revision: draftRevision } : {}),
        idempotency_key: idempotencyKey
      })
    });
  },

  listFoods(query: string): Promise<Food[]> {
    const suffix = query.trim() ? `?q=${encodeURIComponent(query.trim())}` : '';
    return request<Food[]>(`/foods${suffix}`);
  },

  /** AI 识别：返回候选草稿项（全部待确认），不直接落入草稿。 */
  parseDish(text: string): Promise<ParseResponse> {
    return request<ParseResponse>('/foods-parse', {
      method: 'POST',
      body: JSON.stringify({ text })
    });
  },

  getSettings(): Promise<UserSettings> {
    return request<UserSettings>('/settings');
  },

  /** 字段级 PATCH；变更过敏原需 confirmAllergenChange=true。 */
  saveSettings(
    body: Partial<
      Pick<UserSettings, 'dailyTargets' | 'preferences' | 'allergens' | 'avoid'> & {
        confirmAllergenChange: boolean;
      }
    >
  ): Promise<UserSettings> {
    return request<UserSettings>('/settings', {
      method: 'PATCH',
      body: JSON.stringify(body)
    });
  },

  getReport(periodType: 'weekly' | 'monthly', start: string): Promise<PeriodReport> {
    return request<PeriodReport>(`/reports/${periodType}?start=${encodeURIComponent(start)}`);
  },

  /** 让 AI 按本餐目标（全天缺口仅提示）生成这一餐的菜单。 */
  createPlan(date: string, mealType: MealType): Promise<PlanResult> {
    return request<PlanResult>('/plan-sessions', {
      method: 'POST',
      body: JSON.stringify({ meal_date: date, meal_type: mealType })
    });
  },

  /** 在同一会话上做版本化修订（换菜），保存用户反馈与修订历史。 */
  revisePlan(
    sessionId: string,
    currentMenu: PlanItem[],
    constraints: ReplaceConstraintsInput = {},
    userFeedback?: string
  ): Promise<PlanResult> {
    return request<PlanResult>(`/plan-sessions/${sessionId}/messages`, {
      method: 'POST',
      body: JSON.stringify({
        current_menu: currentMenu,
        constraints,
        ...(userFeedback ? { user_feedback: userFeedback } : {})
      })
    });
  },

  /** 原子接受配餐：commit=false 生成可编辑草稿；commit=true 直接记录（单事务+幂等）。 */
  acceptPlan(sessionId: string, commit: boolean, idempotencyKey: string): Promise<AcceptPlanResult> {
    return request<AcceptPlanResult>(`/plan-sessions/${sessionId}/accept`, {
      method: 'POST',
      body: JSON.stringify({ commit, idempotency_key: idempotencyKey })
    });
  }
};
