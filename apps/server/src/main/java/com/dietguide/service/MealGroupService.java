package com.dietguide.service;

import com.dietguide.domain.MealEntry;
import com.dietguide.domain.MealGroup;
import com.dietguide.domain.MealInputItem;
import com.dietguide.domain.NutritionResult;
import com.dietguide.domain.SubmitMealInput;
import com.dietguide.domain.SubmitMealResult;
import com.dietguide.domain.MealVersion;
import com.dietguide.nutrition.NutritionEngine;
import com.dietguide.shared.AppException;
import com.dietguide.shared.Hashing;
import com.dietguide.shared.Nutrients;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MealGroupService {

    private final MealGroupStore store;
    private final NutritionEngine engine;
    private final ObjectMapper mapper;

    public MealGroupService(MealGroupStore store, NutritionEngine engine, ObjectMapper mapper) {
        this.store = store;
        this.engine = engine;
        this.mapper = mapper;
    }

    public SubmitMealResult submit(SubmitMealInput input) {
        String requestHash = Hashing.sha256Hex(Hashing.canonicalJson(input, mapper));
        SubmitMealResult cached = store.findIdempotent(input.userId(), input.idempotencyKey(), requestHash);
        if (cached != null) {
            return cached;
        }

        if (!"append".equals(input.mode()) && !"replace".equals(input.mode())) {
            throw AppException.validation("mode must be append or replace");
        }
        if ("replace".equals(input.mode()) && input.baseRevision() == null) {
            throw AppException.validation("replace requires baseRevision");
        }

        List<MealGroup> groups = store.listGroups(input.userId(), input.mealDate());
        int instanceSeq = input.instanceSeq() != null ? input.instanceSeq() : resolveInstanceSeq(input, groups);

        MealGroup existing = groups.stream()
                .filter(group -> group.mealType().equals(input.mealType()) && group.instanceSeq() == instanceSeq)
                .findFirst()
                .orElse(null);

        MealGroup group = existing != null
                ? existing
                : store.createGroup(new MealGroup.Draft(
                        input.userId(),
                        input.mealDate(),
                        input.mealType(),
                        instanceSeq,
                        "active"
                ));

        List<MealEntry> entries = store.listEntries(group.id());
        int revision = MealVersion.resolveNextRevision(entries, input.mode(), input.baseRevision());

        NutritionResult nutrition = engine.computeMeal(safeItems(input.items()), input.mealType());

        List<MealEntry.Item> items = new ArrayList<>();
        for (var perItem : nutrition.quantitative().perItem()) {
            items.add(new MealEntry.Item(
                    perItem.foodId(),
                    perItem.recipeId(),
                    perItem.rawName(),
                    perItem.amountG(),
                    perItem.valueType(),
                    perItem.values(),
                    findAnalysis(input.items(), perItem.rawName())
            ));
        }

        MealEntry entry = store.insertEntry(new MealGroupStore.MealEntryDraft(
                group.id(),
                input.userId(),
                input.mode(),
                revision,
                input.baseRevision(),
                nutrition.quantitative().values(),
                nutrition.valueType(),
                items
        ));

        List<MealEntry> allEntries = new ArrayList<>(entries);
        allEntries.add(entry);
        List<MealEntry> effective = MealVersion.effectiveEntries(allEntries);
        Map<String, Double> dailyTotals = NutritionEngine.sumEntryTotals(
                effective.stream().map(MealEntry::totals).toList()
        );

        SubmitMealResult result = new SubmitMealResult(group, entry, effective, dailyTotals);
        store.saveIdempotent(input.userId(), input.idempotencyKey(), requestHash, result);
        store.upsertDailyStats(input.userId(), input.mealDate(), dailyTotals, nutrition.valueType());
        store.markPeriodReportsStale(input.userId(), input.mealDate());
        return result;
    }

    public DaySummary getDay(String userId, LocalDate mealDate) {
        List<MealGroup> groups = store.listGroups(userId, mealDate);
        List<GroupDay> views = new ArrayList<>();
        List<Map<String, Double>> totals = new ArrayList<>();

        for (MealGroup group : groups) {
            List<MealEntry> entries = store.listEntries(group.id());
            List<MealEntry> effective = MealVersion.effectiveEntries(entries);
            views.add(new GroupDay(group, entries, effective));
            effective.forEach(entry -> totals.add(entry.totals()));
        }

        return new DaySummary(views, NutritionEngine.sumEntryTotals(totals));
    }

    private static int resolveInstanceSeq(SubmitMealInput input, List<MealGroup> groups) {
        if (!"snack".equals(input.mealType())) {
            return 1;
        }
        return groups.stream()
                .filter(group -> "snack".equals(group.mealType()))
                .mapToInt(MealGroup::instanceSeq)
                .max()
                .orElse(0) + 1;
    }

    private static List<MealInputItem> safeItems(List<MealInputItem> items) {
        return items == null ? List.of() : items;
    }

    private static com.dietguide.domain.DishAnalysis findAnalysis(List<MealInputItem> items, String rawName) {
        if (items == null) {
            return null;
        }
        for (MealInputItem item : items) {
            if (rawName.equals(item.rawName())) {
                return item.analysis();
            }
        }
        return null;
    }

    public record GroupDay(MealGroup group, List<MealEntry> entries, List<MealEntry> effective) {
    }

    public record DaySummary(List<GroupDay> groups, Map<String, Double> dailyTotals) {
    }
}
