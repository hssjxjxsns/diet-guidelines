export type MealType = 'breakfast' | 'lunch' | 'dinner' | 'snack';

export type ValueType = 'measured' | 'estimated' | 'ai_estimated';

export interface Nutrients {
  energyKcal?: number;
  proteinG?: number;
  fatG?: number;
  carbG?: number;
  fiberG?: number;
  sodiumMg?: number;
  calciumMg?: number;
  ironMg?: number;
}

export interface MealEntryItem {
  foodId?: string;
  recipeId?: string;
  rawName: string;
  amountG: number;
  valueType: ValueType;
  nutrients: Nutrients;
}

export interface MealEntry {
  id: string;
  groupId: string;
  userId: string;
  mode: 'append' | 'replace';
  revision: number;
  baseRevision?: number;
  totals: Nutrients;
  valueType: ValueType;
  items: MealEntryItem[];
  createdAt: string;
}

export interface MealGroup {
  id: string;
  userId: string;
  mealDate: string;
  mealType: MealType;
  instanceSeq: number;
  status: 'active' | 'closed';
  createdAt: string;
}

export interface DaySummary {
  groups: Array<{ group: MealGroup; entries: MealEntry[]; effective: MealEntry[] }>;
  dailyTotals: Nutrients;
}

export interface SubmitMealItemInput {
  foodId?: string;
  recipeId?: string;
  rawName: string;
  amountG: number;
  valueType?: ValueType;
}

export interface SubmitMealRequest {
  mode: 'append' | 'replace';
  /** replace 必填，取当前最新 revision，用于并发冲突检测。 */
  base_revision?: number;
  idempotency_key: string;
  items: SubmitMealItemInput[];
  nutrition_data_version?: string;
}

export interface SubmitMealResult {
  group: MealGroup;
  entry: MealEntry;
  effectiveEntries: MealEntry[];
  dailyTotals: Nutrients;
}
