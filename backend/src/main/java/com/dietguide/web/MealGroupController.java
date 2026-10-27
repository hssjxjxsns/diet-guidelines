package com.dietguide.web;

import com.dietguide.domain.MealEntry;
import com.dietguide.domain.MealGroup;
import com.dietguide.domain.MealInputItem;
import com.dietguide.domain.SubmitMealInput;
import com.dietguide.domain.SubmitMealResult;
import com.dietguide.service.MealGroupService;
import com.dietguide.service.MealGroupStore;
import com.dietguide.shared.AppException;
import com.dietguide.web.security.CurrentUserId;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/meal-groups")
public class MealGroupController {

    public record CreateGroupRequest(
            @JsonProperty("meal_date") @JsonAlias("mealDate") LocalDate mealDate,
            @JsonProperty("meal_type") @JsonAlias("mealType") String mealType,
            @JsonProperty("instance_seq") @JsonAlias("instanceSeq") Integer instanceSeq
    ) {
    }

    public record SubmitMealRequest(
            String mode,
            @JsonProperty("base_revision") @JsonAlias("baseRevision") Integer baseRevision,
            @JsonProperty("idempotency_key") @JsonAlias("idempotencyKey") String idempotencyKey,
            List<MealInputItem> items,
            @JsonProperty("nutrition_data_version") @JsonAlias("nutritionDataVersion") String nutritionDataVersion
    ) {
    }

    private final MealGroupService meals;
    private final MealGroupStore store;

    public MealGroupController(MealGroupService meals, MealGroupStore store) {
        this.meals = meals;
        this.store = store;
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

    @PostMapping
    public MealGroup create(@CurrentUserId String userId, @RequestBody CreateGroupRequest request) {
        if (request.mealDate() == null || request.mealType() == null) {
            throw AppException.validation("meal_date and meal_type are required");
        }
        return store.createGroup(new MealGroup.Draft(
                userId,
                request.mealDate(),
                request.mealType(),
                request.instanceSeq() == null ? 1 : request.instanceSeq(),
                "active"
        ));
    }

    @GetMapping("/{groupId}")
    public Map<String, Object> get(@CurrentUserId String userId, @PathVariable String groupId) {
        MealGroup group = store.findGroup(userId, groupId);
        if (group == null) {
            throw AppException.notFound("meal group");
        }

        List<MealEntry> entries = store.listEntries(groupId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("group", group);
        payload.put("entries", entries);
        return payload;
    }

    @PostMapping("/{groupId}/entries")
    public SubmitMealResult submit(
            @CurrentUserId String userId,
            @PathVariable String groupId,
            @RequestBody SubmitMealRequest request
    ) {
        MealGroup group = store.findGroup(userId, groupId);
        if (group == null) {
            throw AppException.notFound("meal group");
        }

        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw AppException.validation("idempotency_key is required");
        }
        if (request.mode() == null) {
            throw AppException.validation("mode is required");
        }

        return meals.submit(new SubmitMealInput(
                userId,
                group.mealDate(),
                group.mealType(),
                group.instanceSeq(),
                request.mode(),
                request.baseRevision(),
                request.idempotencyKey(),
                request.items() == null ? List.of() : request.items(),
                request.nutritionDataVersion() == null ? "demo-1" : request.nutritionDataVersion()
        ));
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException error) {
            throw AppException.validation("date must be YYYY-MM-DD");
        }
    }
}
