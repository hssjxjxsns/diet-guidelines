export type MealType = 'breakfast' | 'lunch' | 'second_main' | 'dinner' | 'snack' | 'night_snack';

export type ValueType = 'measured' | 'estimated' | 'ai_estimated';

export type ConfirmationStatus = 'pending' | 'confirmed' | 'rejected';

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
  aiSource?: string | null;
  confirmationStatus?: string;
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
  status: 'draft' | 'logged' | 'abandoned';
  draftRevision: number;
  expiresAt?: string | null;
  committedAt?: string | null;
  createdAt: string;
}

/** item 级餐次草稿：确认态的唯一载体（ai_source 非空为 AI 项，commit 前必须 confirmed）。 */
export interface SubmitMealResult {
  group: MealGroup;
  entry: MealEntry;
  effectiveEntries: MealEntry[];
  dailyTotals: Nutrients;
}

export interface MealDraftItem {
  id: string;
  groupId: string;
  userId: string;
  foodId?: string | null;
  recipeId?: string | null;
  rawName: string;
  amountG: number;
  valueType: string;
  aiSource?: string | null;
  confirmationStatus: ConfirmationStatus;
  confirmedBy?: string | null;
  confirmedAt?: string | null;
  createdAt: string;
}

export interface StructureResult {
  foodGroups: Array<{
    group: string;
    amountG: number;
    recommendedRangeG: number[];
    status: string;
  }>;
  distinctFoodCount?: number;
  note?: string;
}

export interface DaySummary {
  groups: Array<{
    group: MealGroup;
    entries: MealEntry[];
    effective: MealEntry[];
    draftItems: MealDraftItem[];
  }>;
  dailyTotals: Nutrients;
  structure?: StructureResult | null;
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

/** AI 识别候选：一律待确认，确认/改量/删除/换食物后才允许进草稿。 */
export interface AiCandidateItem {
  rawName: string;
  foodId?: string | null;
  amountG: number;
  valueType: string;
  aiSource: string;
  confirmationStatus: 'pending';
  requiresUserConfirm: boolean;
}

export interface ParseResponse {
  analysis: DishAnalysis;
  candidateId?: string | null;
  candidateItems: AiCandidateItem[];
  requiresConfirmation: boolean;
}

export interface UserSettings {
  dailyTargets: Nutrients;
  preferences: string[];
  allergens: string[];
  avoid: string[];
}

export interface FoodGroupAdherence {
  group: string;
  averageG: number;
  recommendedRangeG: number[];
  adherenceRate: number;
  status: string;
}

export interface PeriodReport {
  periodType: 'weekly' | 'monthly';
  periodStart: string;
  periodEnd: string;
  stats: {
    days: number;
    total: Nutrients;
    average: Nutrients;
    foodGroups?: FoodGroupAdherence[];
    guideline?: {
      structureNonEmptyRate?: number;
      diversityOkDays?: number;
      oilWithinLimitDays?: number;
      sodiumAverageMg?: number;
      sodiumWithinLimit?: boolean;
      conclusion?: string;
    };
  };
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
  /** source ∈ AI | RULE_FALLBACK:版本号（如 AI:2）。 */
  source?: string;
}

export interface AcceptPlanResult {
  groupId: string;
  sessionId: string;
  status: 'ACCEPTING' | 'LOGGED';
  result?: {
    group: MealGroup;
    entry: MealEntry;
    effectiveEntries: MealEntry[];
    dailyTotals: Nutrients;
  } | null;
}
