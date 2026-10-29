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
  /** AI 直接估算时携带结构化结果，由后端换算区间营养。 */
  analysis?: DishAnalysis;
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

export interface Food {
  id: string;
  name: string;
  aliases?: string[];
  category?: string;
  nutrientsPer100g: Nutrients;
  edibleRatio: number;
  tags?: string[];
  allergens?: string[];
}

export interface DishAnalysis {
  dishName?: string;
  matchedFoodId?: string;
  recipeProposal?: {
    ingredients: Array<{ name: string; foodId?: string; amountG?: number }>;
    oilG?: number;
    cookingMethod?: string;
    servings?: number;
    yieldG?: number;
  };
  directEstimate?: { energyKcal?: number[]; proteinG?: number[]; confidence?: number };
  confidence?: number;
  basis?: string[];
  needsUserConfirm?: boolean;
}

export interface UserSettings {
  dailyTargets: Nutrients;
  preferences: string[];
  allergens: string[];
  avoid: string[];
}

export interface PeriodReport {
  periodType: 'weekly' | 'monthly';
  periodStart: string;
  periodEnd: string;
  stats: { days: number; total: Nutrients; average: Nutrients };
  aiSummary?: string;
  status?: string;
}

export interface PlanItem {
  foodId?: string;
  recipeId?: string;
  name: string;
  amountG: number;
  foodGroup?: string;
  tags?: string[];
  allergens?: string[];
  reason?: string;
  citations?: string[];
}

export interface ReplaceConstraintsInput {
  vegetarian?: boolean;
  noSpicy?: boolean;
  requiredFoodGroups?: string[];
  minProteinG?: number;
  maxEnergyKcal?: number;
}

export interface PlanResult {
  sessionId: string;
  items: PlanItem[];
  explanation?: string;
  citations?: string[];
  guidelines?: Array<{ id: string; title: string; score?: number | null }>;
  validation?: {
    passed?: boolean;
    failedRules?: Array<{ ruleId?: string; message?: string; severity?: string }>;
    warnings?: Array<{ ruleId?: string; message?: string }>;
  };
  gap?: { remaining?: Nutrients; unloggedMeals?: string[] };
  fallback?: boolean;
}
