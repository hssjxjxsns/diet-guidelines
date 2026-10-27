import { IdempotencyConflictError } from '../../src/shared/errors.ts';
import type { MealEntry, MealGroup, SubmitMealResult } from '../../src/shared/types.ts';
import type { MealGroupStore } from '../../src/services/meal-groups.ts';

export class InMemoryMealGroupStore implements MealGroupStore {
  groups: MealGroup[] = [];
  entries: MealEntry[] = [];
  idempotency = new Map<string, { requestHash: string; result: SubmitMealResult }>();

  async listGroups(userId: string, mealDate: string): Promise<MealGroup[]> {
    return this.groups.filter((group) => group.userId === userId && group.mealDate === mealDate);
  }

  async createGroup(input: Omit<MealGroup, 'id' | 'createdAt'>): Promise<MealGroup> {
    const existing = this.groups.find(
      (group) =>
        group.userId === input.userId &&
        group.mealDate === input.mealDate &&
        group.mealType === input.mealType &&
        group.instanceSeq === input.instanceSeq
    );
    if (existing) return existing;

    const group: MealGroup = {
      ...input,
      id: crypto.randomUUID(),
      createdAt: new Date().toISOString()
    };
    this.groups.push(group);
    return group;
  }

  async listEntries(groupId: string): Promise<MealEntry[]> {
    return this.entries
      .filter((entry) => entry.groupId === groupId)
      .sort((left, right) => left.revision - right.revision);
  }

  async insertEntry(input: Omit<MealEntry, 'id' | 'createdAt'>): Promise<MealEntry> {
    const entry: MealEntry = {
      ...input,
      id: crypto.randomUUID(),
      createdAt: new Date().toISOString()
    };
    this.entries.push(entry);
    return entry;
  }

  async findIdempotent(
    userId: string,
    key: string,
    requestHash: string
  ): Promise<SubmitMealResult | null> {
    const cached = this.idempotency.get(`${userId}:${key}`);
    if (!cached) return null;
    if (cached.requestHash !== requestHash) throw new IdempotencyConflictError();
    return cached.result;
  }

  async saveIdempotent(
    userId: string,
    key: string,
    requestHash: string,
    result: SubmitMealResult
  ): Promise<void> {
    this.idempotency.set(`${userId}:${key}`, { requestHash, result });
  }
}
