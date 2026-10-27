package com.dietguide.domain;

import com.dietguide.shared.AppException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 统计口径：最新一条 replace + 其后所有 append。
 * 若从未出现过 replace，则所有 append 都有效。
 */
public final class MealVersion {

    private MealVersion() {
    }

    public static int currentRevision(List<? extends Versioned> entries) {
        return entries.stream().mapToInt(Versioned::revision).max().orElse(0);
    }

    public static <T extends Versioned> List<T> effectiveEntries(List<T> entries) {
        int latestReplace = entries.stream()
                .filter(entry -> "replace".equals(entry.mode()))
                .mapToInt(Versioned::revision)
                .max()
                .orElse(0);

        List<T> effective = new ArrayList<>();
        for (T entry : entries) {
            if (entry.revision() >= latestReplace) {
                effective.add(entry);
            }
        }
        effective.sort(Comparator.comparingInt(Versioned::revision));
        return effective;
    }

    public static int resolveNextRevision(
            List<? extends Versioned> entries,
            String mode,
            Integer baseRevision
    ) {
        int revision = currentRevision(entries);
        if ("replace".equals(mode) && (baseRevision == null || baseRevision != revision)) {
            throw AppException.mealRevisionConflict(revision);
        }
        return revision + 1;
    }
}
