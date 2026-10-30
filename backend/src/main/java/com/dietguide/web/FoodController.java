package com.dietguide.web;

import com.dietguide.dish.DishAnalyzer;
import com.dietguide.domain.DishAnalysis;
import com.dietguide.service.RecipeCandidateStore;
import com.dietguide.shared.AppException;
import com.dietguide.web.security.CurrentUserId;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 识别输出一律是候选：响应携带 candidateItems（confirmation_status=pending），
 * 必须经用户确认/修改份量/删除/换食物后才能 commit 进正式记录（R09 硬拦未确认项）。
 * AI 结果不直接拆成正式草稿事实。
 */
@RestController
@RequestMapping("/foods-parse")
public class FoodController {

    public record ParseRequest(
            String text,
            @JsonProperty("image_url") @JsonAlias("imageUrl") String imageUrl,
            List<String> hints
    ) {
    }

    private final DishAnalyzer analyzer;
    private final RecipeCandidateStore candidates;

    public FoodController(DishAnalyzer analyzer, RecipeCandidateStore candidates) {
        this.analyzer = analyzer;
        this.candidates = candidates;
    }

    @PostMapping
    public Map<String, Object> parse(@CurrentUserId String userId, @RequestBody ParseRequest request) {
        if ((request.text() == null || request.text().isBlank())
                && (request.imageUrl() == null || request.imageUrl().isBlank())) {
            throw AppException.validation("text or image_url is required");
        }

        DishAnalysis analysis = analyzer.analyze(new DishAnalyzer.AnalyzeInput(
                request.text(),
                request.imageUrl(),
                request.hints()
        ));

        String candidateId = analysis.recipeProposal() != null
                ? candidates.save(userId, analysis)
                : null;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("analysis", analysis);
        payload.put("candidateId", candidateId);
        payload.put("candidateItems", buildCandidateItems(analysis, candidateId));
        payload.put("requiresConfirmation", true);
        return payload;
    }

    /**
     * 候选草稿项：全部 confirmation_status=pending、valueType=ai_estimated，
     * 由前端 AiCandidateSheet 逐项"确认/改份量/删除/换食物"后写入 meal_draft_items。
     */
    private static List<Map<String, Object>> buildCandidateItems(DishAnalysis analysis, String candidateId) {
        List<Map<String, Object>> items = new ArrayList<>();
        String aiSource = analysis.recipeProposal() != null && candidateId != null
                ? "recipe-candidate:" + candidateId
                : "foods-parse";

        if (analysis.recipeProposal() != null
                && analysis.recipeProposal().ingredients() != null
                && !analysis.recipeProposal().ingredients().isEmpty()) {
            for (var ingredient
                    : analysis.recipeProposal().ingredients()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("rawName", ingredient.name());
                item.put("foodId", ingredient.foodId());
                item.put("amountG", ingredient.amountG() == null ? 100.0 : ingredient.amountG());
                item.put("valueType", "ai_estimated");
                item.put("aiSource", aiSource);
                item.put("confirmationStatus", "pending");
                item.put("requiresUserConfirm", true);
                items.add(item);
            }
            double oilG = analysis.recipeProposal().oilG() == null ? 0 : analysis.recipeProposal().oilG();
            if (oilG > 0) {
                Map<String, Object> oil = new LinkedHashMap<>();
                oil.put("rawName", "植物油");
                oil.put("foodId", "f_oil_001");
                oil.put("amountG", oilG);
                oil.put("valueType", "ai_estimated");
                oil.put("aiSource", aiSource);
                oil.put("confirmationStatus", "pending");
                oil.put("requiresUserConfirm", true);
                items.add(oil);
            }
            return items;
        }

        Map<String, Object> estimate = new LinkedHashMap<>();
        estimate.put("rawName", analysis.dishName());
        estimate.put("foodId", analysis.matchedFoodId());
        estimate.put("amountG", 100.0);
        estimate.put("valueType", "ai_estimated");
        estimate.put("aiSource", aiSource);
        estimate.put("confirmationStatus", "pending");
        estimate.put("requiresUserConfirm", true);
        items.add(estimate);
        return items;
    }
}
