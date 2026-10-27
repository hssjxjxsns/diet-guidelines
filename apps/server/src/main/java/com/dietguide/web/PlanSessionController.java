package com.dietguide.web;

import com.dietguide.domain.PlanItem;
import com.dietguide.domain.PlanResult;
import com.dietguide.domain.ReplaceConstraints;
import com.dietguide.service.PlanSessionStore;
import com.dietguide.service.PlanningService;
import com.dietguide.shared.AppException;
import com.dietguide.web.security.CurrentUserId;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/plan-sessions")
public class PlanSessionController {

    public record CreatePlanRequest(
            @JsonProperty("meal_date") @JsonAlias("mealDate") LocalDate mealDate,
            @JsonProperty("meal_type") @JsonAlias("mealType") String mealType
    ) {
    }

    public record ReviseRequest(
            @JsonProperty("current_menu") @JsonAlias("currentMenu") List<PlanItem> currentMenu,
            ReplaceConstraints constraints
    ) {
    }

    private final PlanningService planning;
    private final PlanSessionStore sessions;

    public PlanSessionController(PlanningService planning, PlanSessionStore sessions) {
        this.planning = planning;
        this.sessions = sessions;
    }

    @PostMapping
    public PlanResult create(@CurrentUserId String userId, @RequestBody CreatePlanRequest request) {
        if (request.mealDate() == null || request.mealType() == null) {
            throw AppException.validation("meal_date and meal_type are required");
        }
        return planning.create(new PlanningService.CreatePlanInput(
                userId,
                request.mealDate(),
                request.mealType()
        ));
    }

    @GetMapping("/{sessionId}")
    public Map<String, Object> get(@CurrentUserId String userId, @PathVariable String sessionId) {
        Map<String, Object> session = sessions.find(userId, sessionId);
        if (session == null) {
            throw AppException.notFound("session");
        }
        return session;
    }

    @PostMapping("/{sessionId}/messages")
    public PlanResult revise(
            @CurrentUserId String userId,
            @PathVariable String sessionId,
            @RequestBody ReviseRequest request
    ) {
        Map<String, Object> session = sessions.find(userId, sessionId);
        if (session == null) {
            throw AppException.notFound("session");
        }

        LocalDate mealDate;
        try {
            mealDate = LocalDate.parse(String.valueOf(session.get("meal_date")));
        } catch (DateTimeParseException error) {
            throw AppException.validation("session has invalid meal_date");
        }

        return planning.revise(new PlanningService.RevisePlanInput(
                userId,
                mealDate,
                String.valueOf(session.get("meal_type")),
                request.currentMenu() == null ? List.of() : request.currentMenu(),
                request.constraints() == null
                        ? new ReplaceConstraints(null, null, null, null, null)
                        : request.constraints()
        ));
    }

    @PostMapping("/{sessionId}/complete")
    public Map<String, Object> complete(@CurrentUserId String userId, @PathVariable String sessionId) {
        Map<String, Object> session = sessions.find(userId, sessionId);
        if (session == null) {
            throw AppException.notFound("session");
        }
        sessions.complete(userId, sessionId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        return payload;
    }
}
