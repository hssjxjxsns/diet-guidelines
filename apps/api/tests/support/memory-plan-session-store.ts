import type { PlanItem, ValidationResult } from '../../src/shared/types.ts';
import type { PlanSessionStore } from '../../src/services/planning.ts';

export class InMemoryPlanSessionStore implements PlanSessionStore {
  saved: Array<{
    userId: string;
    mealDate: string;
    mealType: string;
    status: 'planning' | 'awaiting_log';
    contextSnapshot: Record<string, unknown>;
    validationResult: ValidationResult;
    items: PlanItem[];
  }> = [];

  async save(input: (typeof this.saved)[number]): Promise<string> {
    this.saved.push(input);
    return `session-${this.saved.length}`;
  }
}
