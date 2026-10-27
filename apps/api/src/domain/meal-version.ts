import { MealRevisionConflictError } from '../shared/errors.ts';
import type { MealEntryMode } from '../shared/types.ts';

export interface VersionedEntry {
  revision: number;
  mode: MealEntryMode;
}

export function currentRevision(entries: VersionedEntry[]): number {
  return entries.reduce((max, entry) => Math.max(max, entry.revision), 0);
}

/**
 * 有效集合 = 最新一条 replace + 其后所有 append。
 * 若从未出现过 replace，则所有 append 都有效。
 */
export function effectiveEntries<T extends VersionedEntry>(entries: T[]): T[] {
  const latestReplaceRevision = entries
    .filter((entry) => entry.mode === 'replace')
    .reduce((max, entry) => Math.max(max, entry.revision), 0);

  return entries
    .filter((entry) => entry.revision >= latestReplaceRevision)
    .sort((left, right) => left.revision - right.revision);
}

export function resolveNextRevision(
  entries: VersionedEntry[],
  mode: MealEntryMode,
  baseRevision: number | undefined
): number {
  const revision = currentRevision(entries);

  if (mode === 'replace' && (baseRevision === undefined || baseRevision !== revision)) {
    throw new MealRevisionConflictError(revision);
  }

  return revision + 1;
}
