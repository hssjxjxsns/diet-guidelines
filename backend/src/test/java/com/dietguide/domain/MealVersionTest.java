package com.dietguide.domain;

import com.dietguide.shared.AppException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MealVersionTest {

    private record Entry(String id, String mode, int revision) implements Versioned {
    }

    @Test
    void keepsAllAppendsWhenNoReplaceExists() {
        List<Entry> entries = List.of(
                new Entry("a", "append", 1),
                new Entry("b", "append", 2)
        );

        assertEquals(List.of(1, 2), MealVersion.effectiveEntries(entries).stream().map(Entry::revision).toList());
    }

    @Test
    void resetsEffectiveSetAtLatestReplace() {
        List<Entry> entries = List.of(
                new Entry("a", "append", 1),
                new Entry("b", "replace", 2),
                new Entry("c", "append", 3)
        );

        List<Entry> effective = MealVersion.effectiveEntries(entries);

        assertEquals(List.of(2, 3), effective.stream().map(Entry::revision).toList());
    }

    @Test
    void rejectsStaleBaseRevisionForReplace() {
        List<Entry> entries = List.of(new Entry("a", "append", 1));

        AppException error = assertThrows(
                AppException.class,
                () -> MealVersion.resolveNextRevision(entries, "replace", 0)
        );

        assertEquals("MEAL_REVISION_CONFLICT", error.code());
    }

    @Test
    void incrementsRevisionAfterCurrentEntry() {
        List<Entry> entries = List.of(new Entry("a", "append", 2));

        assertEquals(3, MealVersion.resolveNextRevision(entries, "replace", 2));
    }
}
