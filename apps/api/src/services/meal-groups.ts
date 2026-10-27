import { NutritionEngine, sumEntryTotals } from '../nutrition/engine.ts';
import { resolveNextRevision, effectiveEntries } from '../domain/meal-version.ts';
import { stableStringify, sha256Hex } from '../shared/hash.ts';
import type {
  MealEntry,
  MealGroup,
  SubmitMealInput,
  SubmitMealResult
} from '../shared/types.ts';

export interface MealGroupStore {
  listGroups(userId: string, mealDate: string): Promise<MealGroup[]>;
  createGroup(input: Omit<MealGroup, 'id' | 'createdAt'>): Promise<MealGroup>;
  listEntries(groupId: string): Promise<MealEntry[]>;
  insertEntry(input: Omit<MealEntry, 'id' | 'createdAt'>): Promise<MealEntry>;
  findIdempotent(userId: string, key: string, requestHash: string): Promise<SubmitMealResult | null>;
  saveIdempotent(
    userId: string,
    key: string,
    requestHash: string,
    result: SubmitMealResult
  ): Promise<void>;
}

export class MealGroupService {
  constructor(
    private readonly store: MealGroupStore,
    private readonly engine: NutritionEngine
  ) {}

  async submit(input: SubmitMealInput): Promise<SubmitMealResult> {
    const requestHash = await sha256Hex(stableStringify(input));
    const cached = await this.store.findIdempotent(input.userId, input.idempotencyKey, requestHash);
    if (cached) return cached;

    if (input.mode === 'replace' && input.baseRevision === undefined) {
      throw new Error('replace requires baseRevision');
    }

    const groups = await this.store.listGroups(input.userId, input.mealDate);
    const instanceSeq = input.instanceSeq ?? resolveInstanceSeq(input, groups);
    const existingGroup = groups.find(
      (group) =>
        group.mealType === input.mealType &&
        group.instanceSeq === instanceSeq
    );

    const group =
      existingGroup ??
      (await this.store.createGroup({
        userId: input.userId,
        mealDate: input.mealDate,
        mealType: input.mealType,
        instanceSeq,
        status: 'active'
      }));

    const entries = await this.store.listEntries(group.id);
    const revision = resolveNextRevision(entries, input.mode, input.baseRevision);

    const nutrition = await this.engine.computeMeal(input.items, input.mealType);

    const entry = await this.store.insertEntry({
      groupId: group.id,
      userId: input.userId,
      mode: input.mode,
      revision,
      baseRevision: input.baseRevision,
      totals: nutrition.quantitative.values,
      valueType: nutrition.valueType,
      items: nutrition.quantitative.perItem.map((item) => ({
        foodId: item.foodId,
        recipeId: item.recipeId,
        rawName: item.rawName,
        amountG: item.amountG,
        valueType: item.valueType,
        nutrients: item.values,
        analysis: input.items.find((source) => source.rawName === item.rawName)?.analysis
      }))
    });

    const allEntries = [...entries, entry];
    const effective = effectiveEntries(allEntries);
    const dailyTotals = sumEntryTotals(effective.map((item) => item.totals));

    const result: SubmitMealResult = {
      group,
      entry,
      effectiveEntries: effective,
      dailyTotals
    };

    await this.store.saveIdempotent(input.userId, input.idempotencyKey, requestHash, result);
    return result;
  }

  async getDay(userId: string, mealDate: string): Promise<{
    groups: Array<{ group: MealGroup; entries: MealEntry[]; effective: MealEntry[] }>;
    dailyTotals: ReturnType<typeof sumEntryTotals>;
  }> {
    const groups = await this.store.listGroups(userId, mealDate);
    const result = [];

    for (const group of groups) {
      const entries = await this.store.listEntries(group.id);
      result.push({ group, entries, effective: effectiveEntries(entries) });
    }

    return {
      groups: result,
      dailyTotals: sumEntryTotals(result.flatMap((item) => item.effective.map((entry) => entry.totals)))
    };
  }
}

function resolveInstanceSeq(input: SubmitMealInput, groups: MealGroup[]): number {
  if (input.mealType !== 'snack') return 1;
  const snackSeqs = groups
    .filter((group) => group.mealType === 'snack')
    .map((group) => group.instanceSeq);
  return snackSeqs.length === 0 ? 1 : Math.max(...snackSeqs) + 1;
}
