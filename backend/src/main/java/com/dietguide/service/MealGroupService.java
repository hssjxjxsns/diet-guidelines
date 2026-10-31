package com.dietguide.service;

import com.dietguide.domain.MealDraftItem;
import com.dietguide.domain.MealEntry;
import com.dietguide.domain.MealGroup;
import com.dietguide.domain.MealInputItem;
import com.dietguide.domain.NutritionResult;
import com.dietguide.domain.StructureResult;
import com.dietguide.domain.SubmitMealInput;
import com.dietguide.domain.SubmitMealResult;
import com.dietguide.domain.MealVersion;
import com.dietguide.domain.UserSettings;
import com.dietguide.domain.ValidationResult;
import com.dietguide.nutrition.NutritionEngine;
import com.dietguide.shared.AppException;
import com.dietguide.shared.Hashing;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记录主流程：记录是唯一主流程，无"先建空组"。
 * 开餐（惰性建 draft 组）→ item 级草稿编辑（乐观锁）→ commit（事务+幂等+规则校验）→ 聚合 → 报表 stale。
 */
@Service
public class MealGroupService {

    private static final long DRAFT_TTL_HOURS = 24;

    private final MealGroupStore store;
    private final NutritionEngine engine;
    private final DailyStatsAggregator aggregator;
    private final RecordValidationService validation;
    private final SettingsStore settings;
    private final ObjectMapper mapper;

    public MealGroupService(
            MealGroupStore store,
            NutritionEngine engine,
            DailyStatsAggregator aggregator,
            RecordValidationService validation,
            SettingsStore settings,
            ObjectMapper mapper
    ) {
        this.store = store;
        this.engine = engine;
        this.aggregator = aggregator;
        this.validation = validation;
        this.settings = settings;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public DaySummary getDay(String userId, LocalDate mealDate) {
        List<MealGroup> groups = expireAndGetGroups(userId, mealDate);
        List<GroupDay> views = new ArrayList<>();

        for (MealGroup group : groups) {
            if ("abandoned".equals(group.status())) {
                continue;
            }
            List<MealEntry> entries = store.listEntries(group.id());
            List<MealEntry> effective = MealVersion.effectiveEntries(entries);
            views.add(new GroupDay(group, entries, effective, store.listDraftItems(group.id())));
        }

        DailyStatsAggregator.DailyStats stats = aggregateDay(userId, mealDate);
        return new DaySummary(views, stats.totals(), stats.structure());
    }

    public Map<String, Object> getGroup(String userId, String groupId) {
        MealGroup group = store.findGroup(userId, groupId);
        if (group == null) {
            throw AppException.notFound("meal group");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("group", group);
        payload.put("entries", store.listEntries(groupId));
        payload.put("draftItems", store.listDraftItems(groupId));
        return payload;
    }

    // ------------------------------------------------------------------
    // 草稿主流程（唯一入口：惰性建组，不暴露空组创建）
    // ------------------------------------------------------------------

    /** 开餐：惰性建 draft 分组（带 24h 过期）；已有草稿复用，已记录组重开为草稿（保留版本链）。 */
    public MealGroup ensureDraft(String userId, LocalDate mealDate, String mealType, Integer instanceSeq) {
        expireDrafts(userId, mealDate);
        List<MealGroup> groups = store.listGroups(userId, mealDate);

        if (instanceSeq != null) {
            MealGroup draft = groups.stream()
                    .filter(group -> group.mealType().equals(mealType)
                            && group.instanceSeq() == instanceSeq
                            && "draft".equals(group.status()))
                    .findFirst()
                    .orElse(null);
            if (draft != null) {
                return draft;
            }
            MealGroup logged = groups.stream()
                    .filter(group -> group.mealType().equals(mealType)
                            && group.instanceSeq() == instanceSeq
                            && "logged".equals(group.status()))
                    .findFirst()
                    .orElse(null);
            if (logged != null) {
                store.reopenDraft(userId, logged.id());
                return store.findGroup(userId, logged.id());
            }
            return store.createGroup(new MealGroup.Draft(userId, mealDate, mealType, instanceSeq, "draft"));
        }

        if (isMultiInstance(mealType)) {
            int seq = nextInstanceSeq(userId, mealDate, mealType);
            return store.createGroup(new MealGroup.Draft(userId, mealDate, mealType, seq, "draft"));
        }

        MealGroup draft = groups.stream()
                .filter(group -> group.mealType().equals(mealType) && "draft".equals(group.status()))
                .findFirst()
                .orElse(null);
        if (draft != null) {
            return draft;
        }
        MealGroup logged = groups.stream()
                .filter(group -> group.mealType().equals(mealType) && "logged".equals(group.status()))
                .findFirst()
                .orElse(null);
        if (logged != null) {
            store.reopenDraft(userId, logged.id());
            return store.findGroup(userId, logged.id());
        }
        return store.createGroup(new MealGroup.Draft(userId, mealDate, mealType, 1, "draft"));
    }

    private static boolean isMultiInstance(String mealType) {
        return "snack".equals(mealType) || "night_snack".equals(mealType);
    }

    @Transactional
    public MealDraftItem addDraftItem(
            String userId,
            String groupId,
            MealDraftItem item,
            Integer expectedDraftRevision
    ) {
        requireDraftGroup(userId, groupId);
        store.bumpDraftRevision(userId, groupId, expectedDraftRevision);
        return store.insertDraftItem(new MealDraftItem(
                null,
                groupId,
                userId,
                item.foodId(),
                item.recipeId(),
                item.rawName(),
                item.amountG(),
                item.valueType(),
                item.analysis(),
                item.aiSource(),
                item.isAiSourced() && !"confirmed".equals(item.confirmationStatus())
                        ? "pending"
                        : "confirmed",
                item.confirmedBy(),
                item.confirmedAt(),
                null
        ));
    }

    @Transactional
    public MealDraftItem updateDraftItem(
            String userId,
            String groupId,
            String itemId,
            MealDraftItem patch,
            Integer expectedDraftRevision
    ) {
        MealGroup group = requireDraftGroup(userId, groupId);
        MealDraftItem current = store.listDraftItems(groupId).stream()
                .filter(item -> item.id().equals(itemId))
                .findFirst()
                .orElseThrow(() -> AppException.notFound("draft item"));

        // 确认动作幂等：目标 confirmation_status 与当前一致时直接返回，不递增版本
        boolean confirmationOnly = patch.amountG() == 0 && patch.rawName() == null;
        if (confirmationOnly && patch.confirmationStatus() != null
                && patch.confirmationStatus().equals(current.confirmationStatus())) {
            return current;
        }

        store.bumpDraftRevision(userId, groupId, expectedDraftRevision);
        String status = patch.confirmationStatus() != null ? patch.confirmationStatus() : current.confirmationStatus();
        String confirmedBy = "confirmed".equals(status) ? userId : current.confirmedBy();
        String confirmedAt = "confirmed".equals(status) && current.confirmedAt() == null
                ? OffsetDateTime.now().toString()
                : current.confirmedAt();

        return store.updateDraftItem(new MealDraftItem(
                current.id(),
                current.groupId(),
                current.userId(),
                patch.foodId() != null ? patch.foodId() : current.foodId(),
                patch.recipeId() != null ? patch.recipeId() : current.recipeId(),
                patch.rawName() != null ? patch.rawName() : current.rawName(),
                patch.amountG() > 0 ? patch.amountG() : current.amountG(),
                patch.valueType() != null ? patch.valueType() : current.valueType(),
                patch.analysis() != null ? patch.analysis() : current.analysis(),
                current.aiSource(),
                status,
                confirmedBy,
                confirmedAt,
                current.createdAt()
        ));
    }

    @Transactional
    public void removeDraftItem(String userId, String groupId, String itemId, Integer expectedDraftRevision) {
        requireDraftGroup(userId, groupId);
        store.bumpDraftRevision(userId, groupId, expectedDraftRevision);
        store.deleteDraftItem(userId, itemId);
    }

    /** 复制上一餐：把最近一条已提交（logged）餐次的有效 items 复制进当前草稿（非 AI 来源）。 */
    @Transactional
    public List<MealDraftItem> copyPrevious(String userId, String groupId, Integer expectedDraftRevision) {
        MealGroup group = requireDraftGroup(userId, groupId);

        MealGroup previous = store.listGroups(userId, group.mealDate()).stream()
                .filter(candidate -> "logged".equals(candidate.status()) && !candidate.id().equals(groupId))
                .findFirst()
                .orElse(null);
        if (previous == null) {
            for (int daysBack = 1; daysBack <= 7 && previous == null; daysBack += 1) {
                LocalDate candidateDate = group.mealDate().minusDays(daysBack);
                previous = store.listGroups(userId, candidateDate).stream()
                        .filter(candidate -> "logged".equals(candidate.status()))
                        .findFirst()
                        .orElse(null);
            }
        }
        if (previous == null) {
            throw AppException.validation("没有可复制的历史餐次");
        }

        store.bumpDraftRevision(userId, groupId, expectedDraftRevision);
        List<MealDraftItem> copies = new ArrayList<>();
        for (MealEntry entry : MealVersion.effectiveEntries(store.listEntries(previous.id()))) {
            for (MealEntry.Item item : entry.items() == null ? List.<MealEntry.Item>of() : entry.items()) {
                copies.add(store.insertDraftItem(new MealDraftItem(
                        null, groupId, userId, item.foodId(), item.recipeId(), item.rawName(),
                        item.amountG(), item.valueType(), item.analysis(), null, "confirmed", null, null, null
                )));
            }
        }
        return copies;
    }

    /**
     * commit：草稿 → 正式记录的唯一提交用例（acceptPlan 也复用它）。
     * 事务内：幂等 → 草稿校验（归属/过期/乐观锁）→ 规则校验（硬规则 422）→ 写 entry → 聚合 → stale → 草稿转 logged。
     */
    @Transactional
    public SubmitMealResult commitDraft(
            String userId,
            String groupId,
            Integer expectedDraftRevision,
            String idempotencyKey
    ) {
        MealGroup group = requireDraftGroup(userId, groupId);

        String requestHash = Hashing.sha256Hex(Hashing.canonicalJson(Map.of(
                "groupId", groupId,
                "draftRevision", expectedDraftRevision == null ? 0 : expectedDraftRevision
        ), mapper));
        SubmitMealResult cached = store.findIdempotent(userId, idempotencyKey, requestHash);
        if (cached != null) {
            return cached;
        }

        List<MealDraftItem> draftItems = store.listDraftItems(groupId);
        if (draftItems.isEmpty()) {
            throw AppException.validation("草稿为空，不能提交");
        }
        boolean hasUnconfirmed = draftItems.stream().anyMatch(item -> !item.committable());
        if (hasUnconfirmed) {
            throw AppException.validation(
                    "存在未经确认的 AI 估算项，不能提交（R09_AI_UNCONFIRMED）",
                    draftItems.stream()
                            .filter(item -> !item.committable())
                            .map(item -> Map.of("itemId", item.id(), "rawName", item.rawName(),
                                    "ruleId", RecordValidationService.RULE_AI_UNCONFIRMED))
                            .toList()
            );
        }

        List<MealInputItem> items = new ArrayList<>();
        for (MealDraftItem item : draftItems) {
            items.add(toInputItem(item));
        }

        int newDraftRevision = store.bumpDraftRevision(userId, groupId, expectedDraftRevision);

        SubmitMealInput input = new SubmitMealInput(
                userId,
                group.mealDate(),
                group.mealType(),
                group.instanceSeq(),
                "append",
                null,
                idempotencyKey,
                items,
                "demo-1",
                groupId,
                newDraftRevision
        );
        SubmitMealResult result = submit(input);

        store.markGroupLogged(userId, groupId);
        for (MealDraftItem item : draftItems) {
            store.deleteDraftItem(userId, item.id());
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 唯一写路径：submit（惰性建组 / 指定组 + 规则校验 + 事务写 entry + 聚合 + stale + 幂等）
    // ------------------------------------------------------------------

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

        // 记录提交规则校验：与配餐共用同一 RuleEngine；过敏原/忌口/食物有效性/未确认 AI 项硬阻断（422）
        UserSettings userSettings = settings.getSettings(input.userId());
        Map<String, Double> consumed = aggregateDay(input.userId(), input.mealDate()).totals();
        ValidationResult result = validation.validateForRecord(
                safeItems(input.items()),
                consumed,
                userSettings.dailyTargets(),
                input.mealType(),
                userSettings
        );
        if (RecordValidationService.hasHardFailure(result)) {
            throw AppException.validation("记录未通过规则校验", result.failedRules());
        }

        MealGroup group = resolveGroup(input);
        List<MealEntry> entries = store.listEntries(group.id());
        int revision = MealVersion.resolveNextRevision(entries, input.mode(), input.baseRevision());

        NutritionResult nutrition = engine.computeMeal(safeItems(input.items()), input.mealType());

        List<MealEntry.Item> items = new ArrayList<>();
        for (var perItem : nutrition.quantitative().perItem()) {
            MealInputItem source = findByRawName(input.items(), perItem.rawName());
            boolean aiSourced = source != null && source.aiSource() != null;
            items.add(new MealEntry.Item(
                    perItem.foodId(),
                    perItem.recipeId(),
                    perItem.rawName(),
                    perItem.amountG(),
                    perItem.valueType(),
                    perItem.values(),
                    source == null ? null : source.analysis(),
                    aiSourced ? source.aiSource() : null,
                    aiSourced && Boolean.TRUE.equals(source.confirmed()) ? "confirmed" : "confirmed",
                    null,
                    null
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

        // 唯一口径：全日有效 entries 经 DailyStatsAggregator 物化（metrics + structure）
        DailyStatsAggregator.DailyStats stats = aggregateDay(input.userId(), input.mealDate());
        Map<String, Double> dailyTotals = stats.totals();

        SubmitMealResult submitResult = new SubmitMealResult(group, entry, effective, dailyTotals);
        store.saveIdempotent(input.userId(), input.idempotencyKey(), requestHash, submitResult);
        store.upsertDailyStats(input.userId(), input.mealDate(), stats);
        store.markPeriodReportsStale(input.userId(), input.mealDate());
        return submitResult;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private MealGroup resolveGroup(SubmitMealInput input) {
        if (input.groupId() != null) {
            MealGroup group = store.findGroup(input.userId(), input.groupId());
            if (group == null) {
                throw AppException.notFound("meal group");
            }
            return group;
        }

        List<MealGroup> groups = store.listGroups(input.userId(), input.mealDate());
        int instanceSeq = input.instanceSeq() != null ? input.instanceSeq() : nextInstanceSeq(
                input.userId(), input.mealDate(), input.mealType());
        MealGroup existing = groups.stream()
                .filter(group -> group.mealType().equals(input.mealType()) && group.instanceSeq() == instanceSeq)
                .findFirst()
                .orElse(null);
        return existing != null
                ? existing
                : store.createGroup(new MealGroup.Draft(
                        input.userId(),
                        input.mealDate(),
                        input.mealType(),
                        instanceSeq,
                        "draft"
                ));
    }

    /** 非 snack/night_snack 固定 seq=1；snack/night_snack 支持多实例（第二顿夜宵等，取最大序号+1）。 */
    private int nextInstanceSeq(String userId, LocalDate mealDate, String mealType) {
        if (!"snack".equals(mealType) && !"night_snack".equals(mealType)) {
            return 1;
        }
        return store.listGroups(userId, mealDate).stream()
                .filter(group -> mealType.equals(group.mealType()))
                .mapToInt(MealGroup::instanceSeq)
                .max()
                .orElse(0) + 1;
    }

    private List<MealGroup> expireAndGetGroups(String userId, LocalDate mealDate) {
        expireDrafts(userId, mealDate);
        return store.listGroups(userId, mealDate);
    }

    /** 草稿过期清理：expires_at 已过的 draft 组 → abandoned，并清理其草稿项。 */
    private void expireDrafts(String userId, LocalDate mealDate) {
        OffsetDateTime now = OffsetDateTime.now();
        for (MealGroup group : store.listGroups(userId, mealDate)) {
            if (!"draft".equals(group.status()) || group.expiresAt() == null) {
                continue;
            }
            try {
                if (OffsetDateTime.parse(group.expiresAt()).isBefore(now)) {
                    store.markGroupAbandoned(group.id());
                    for (MealDraftItem item : store.listDraftItems(group.id())) {
                        store.deleteDraftItem(group.userId(), item.id());
                    }
                }
            } catch (Exception ignored) {
                // expires_at 解析失败不阻塞读取
            }
        }
    }

    private MealGroup requireDraftGroup(String userId, String groupId) {
        MealGroup group = store.findGroup(userId, groupId);
        if (group == null) {
            throw AppException.notFound("meal group");
        }
        if (!"draft".equals(group.status())) {
            throw AppException.validation("餐次不是可编辑草稿（status=" + group.status() + "）");
        }
        return group;
    }

    private DailyStatsAggregator.DailyStats aggregateDay(String userId, LocalDate mealDate) {
        List<MealEntry> dayEffective = new ArrayList<>();
        for (MealGroup group : store.listGroups(userId, mealDate)) {
            dayEffective.addAll(MealVersion.effectiveEntries(store.listEntries(group.id())));
        }
        return aggregator.compute(dayEffective);
    }

    private static MealInputItem toInputItem(MealDraftItem item) {
        boolean confirmed = !item.isAiSourced() || "confirmed".equals(item.confirmationStatus());
        return new MealInputItem(
                item.foodId(),
                item.recipeId(),
                item.rawName(),
                item.amountG(),
                item.valueType(),
                item.analysis(),
                confirmed,
                item.aiSource()
        );
    }

    private static List<MealInputItem> safeItems(List<MealInputItem> items) {
        return items == null ? List.of() : items;
    }

    private static MealInputItem findByRawName(List<MealInputItem> items, String rawName) {
        if (items == null) {
            return null;
        }
        for (MealInputItem item : items) {
            if (rawName.equals(item.rawName())) {
                return item;
            }
        }
        return null;
    }

    public record GroupDay(
            MealGroup group,
            List<MealEntry> entries,
            List<MealEntry> effective,
            List<MealDraftItem> draftItems
    ) {
    }

    public record DaySummary(
            List<GroupDay> groups,
            Map<String, Double> dailyTotals,
            StructureResult structure
    ) {
    }
}
