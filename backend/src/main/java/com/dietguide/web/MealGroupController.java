package com.dietguide.web;

import com.dietguide.domain.MealDraftItem;
import com.dietguide.domain.MealGroup;
import com.dietguide.domain.SubmitMealResult;
import com.dietguide.service.MealGroupService;
import com.dietguide.shared.AppException;
import com.dietguide.web.security.CurrentUserId;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * 记录主流程端点：开餐（惰性建组）→ item 级草稿编辑 → commit。
 * 不再提供"先建空组"与绕过草稿的直接写入入口。
 */
@RestController
@RequestMapping("/meal-groups")
public class MealGroupController {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OpenDraftRequest(
            @JsonProperty("meal_date") @JsonAlias("mealDate") LocalDate mealDate,
            @JsonProperty("meal_type") @JsonAlias("mealType") String mealType,
            @JsonProperty("instance_seq") @JsonAlias("instanceSeq") Integer instanceSeq
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DraftItemRequest(
            String foodId,
            @JsonProperty("recipe_id") @JsonAlias("recipeId") String recipeId,
            @JsonProperty("raw_name") @JsonAlias("rawName") String rawName,
            @JsonProperty("amount_g") @JsonAlias("amountG") Double amountG,
            @JsonProperty("value_type") @JsonAlias("valueType") String valueType,
            @JsonProperty("ai_source") @JsonAlias("aiSource") String aiSource,
            Boolean confirmed,
            @JsonProperty("draft_revision") @JsonAlias("draftRevision") Integer draftRevision
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DraftItemPatchRequest(
            String foodId,
            @JsonProperty("recipe_id") @JsonAlias("recipeId") String recipeId,
            @JsonProperty("raw_name") @JsonAlias("rawName") String rawName,
            @JsonProperty("amount_g") @JsonAlias("amountG") Double amountG,
            @JsonProperty("value_type") @JsonAlias("valueType") String valueType,
            @JsonProperty("confirmation_status") @JsonAlias("confirmationStatus") String confirmationStatus,
            @JsonProperty("draft_revision") @JsonAlias("draftRevision") Integer draftRevision
    ) {
    }

    public record CommitRequest(
            @JsonProperty("draft_revision") @JsonAlias("draftRevision") Integer draftRevision,
            @JsonProperty("idempotency_key") @JsonAlias("idempotencyKey") String idempotencyKey
    ) {
    }

    private final MealGroupService meals;

    public MealGroupController(MealGroupService meals) {
        this.meals = meals;
    }

    @GetMapping
    public MealGroupService.DaySummary day(
            @CurrentUserId String userId,
            @RequestParam(required = false) String date
    ) {
        if (date == null || date.isBlank()) {
            throw AppException.validation("date is required");
        }
        return meals.getDay(userId, parseDate(date));
    }

    @GetMapping("/{groupId}")
    public Map<String, Object> get(@CurrentUserId String userId, @PathVariable String groupId) {
        return meals.getGroup(userId, groupId);
    }

    /** 开餐：惰性建 draft 分组（24h 过期），同餐次已有活跃草稿则复用。 */
    @PostMapping("/drafts")
    public MealGroup openDraft(@CurrentUserId String userId, @RequestBody OpenDraftRequest request) {
        if (request.mealDate() == null || request.mealType() == null) {
            throw AppException.validation("meal_date and meal_type are required");
        }
        return meals.ensureDraft(userId, request.mealDate(), request.mealType(), request.instanceSeq());
    }

    @PostMapping("/{groupId}/draft/items")
    public MealDraftItem addItem(
            @CurrentUserId String userId,
            @PathVariable String groupId,
            @RequestBody DraftItemRequest request
    ) {
        requireRawName(request.rawName());
        requireAmount(request.amountG());
        return meals.addDraftItem(userId, groupId, new MealDraftItem(
                null, groupId, userId, request.foodId(), request.recipeId(), request.rawName(),
                request.amountG(), request.valueType(), null, request.aiSource(),
                Boolean.TRUE.equals(request.confirmed()) ? "confirmed" : "pending",
                null, null, null
        ), request.draftRevision());
    }

    @PatchMapping("/{groupId}/draft/items/{itemId}")
    public MealDraftItem updateItem(
            @CurrentUserId String userId,
            @PathVariable String groupId,
            @PathVariable String itemId,
            @RequestBody DraftItemPatchRequest request
    ) {
        return meals.updateDraftItem(userId, groupId, itemId, new MealDraftItem(
                itemId, groupId, userId, request.foodId(), request.recipeId(), request.rawName(),
                request.amountG() == null ? 0 : request.amountG(), request.valueType(), null, null,
                request.confirmationStatus(), null, null, null
        ), request.draftRevision());
    }

    @DeleteMapping("/{groupId}/draft/items/{itemId}")
    public Map<String, Object> removeItem(
            @CurrentUserId String userId,
            @PathVariable String groupId,
            @PathVariable String itemId,
            @RequestParam(required = false) Integer draftRevision
    ) {
        meals.removeDraftItem(userId, groupId, itemId, draftRevision);
        return Map.of("ok", true);
    }

    /** 复制上一餐：最近一条已提交餐次的有效 items 复制进当前草稿。 */
    @PostMapping("/{groupId}/draft/copy-previous")
    public Map<String, Object> copyPrevious(
            @CurrentUserId String userId,
            @PathVariable String groupId,
            @RequestBody(required = false) CommitRequest request
    ) {
        List<MealDraftItem> copies = meals.copyPrevious(
                userId, groupId, request == null ? null : request.draftRevision());
        return Map.of("items", copies);
    }

    /** commit：草稿 → 正式记录（规则校验 + 事务 + 幂等 + 聚合 + 报表 stale + 草稿转 logged）。 */
    @PostMapping("/{groupId}/draft/commit")
    public SubmitMealResult commit(
            @CurrentUserId String userId,
            @PathVariable String groupId,
            @RequestBody CommitRequest request
    ) {
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw AppException.validation("idempotency_key is required");
        }
        return meals.commitDraft(userId, groupId, request.draftRevision(), request.idempotencyKey());
    }

    private static void requireRawName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw AppException.validation("raw_name is required");
        }
    }

    private static void requireAmount(Double amountG) {
        if (amountG == null || amountG <= 0) {
            throw AppException.validation("amount_g must be positive");
        }
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException error) {
            throw AppException.validation("date must be YYYY-MM-DD");
        }
    }
}
