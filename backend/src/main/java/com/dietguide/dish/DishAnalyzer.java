package com.dietguide.dish;

import com.dietguide.ai.AiGateway;
import com.dietguide.ai.Prompts;
import com.dietguide.domain.DishAnalysis;
import com.dietguide.domain.Food;
import com.dietguide.nutrition.FoodRepository;
import com.dietguide.shared.AppException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DishAnalyzer {

    public record AnalyzeInput(String text, String imageUrl, List<String> hints) {
    }

    private final AiGateway ai;
    private final FoodRepository foods;
    private final ObjectMapper mapper;

    public DishAnalyzer(AiGateway ai, FoodRepository foods, ObjectMapper mapper) {
        this.ai = ai;
        this.foods = foods;
        this.mapper = mapper;
    }

    public DishAnalysis analyze(AnalyzeInput input) {
        String query = input.text() == null ? "" : input.text().trim();
        List<Food> candidates = query.isEmpty()
                ? foods.listActive(12)
                : foods.search(query, 12);

        DishAnalysis analysis = ai.chatJson(
                new AiGateway.ChatRequest(
                        "food_parse",
                        Prompts.DISH_ANALYSIS_SYSTEM_PROMPT,
                        Prompts.buildDishUserPrompt(input.text(), input.hints(), input.imageUrl(), candidates, mapper),
                        600,
                        0.2,
                        input.imageUrl() == null || input.imageUrl().isBlank() ? null : List.of(input.imageUrl())
                ),
                DishAnalysis.class
        );

        if (Prompts.isBlank(analysis.dishName())) {
            throw AppException.aiInvalidOutput("AI output is missing dishName", null);
        }
        if (analysis.confidence() == null || analysis.confidence() < 0 || analysis.confidence() > 1) {
            throw AppException.aiInvalidOutput("AI output has invalid confidence", null);
        }
        if (analysis.directEstimate() != null) {
            List<Double> energy = analysis.directEstimate().energyKcal();
            if (energy == null || energy.size() != 2 || energy.stream().anyMatch(value -> value == null || value < 0)) {
                throw AppException.aiInvalidOutput("AI output has invalid energy range", null);
            }
        }

        List<String> basis = new ArrayList<>(analysis.basis() == null ? List.of() : analysis.basis());
        String matchedFoodId = analysis.matchedFoodId();

        if (matchedFoodId != null && foods.findById(matchedFoodId) == null) {
            matchedFoodId = null;
            basis.add("AI 返回的食物编号不在食物库中，已忽略");
        }

        return new DishAnalysis(
                analysis.dishName(),
                matchedFoodId,
                analysis.recipeProposal(),
                analysis.directEstimate(),
                analysis.confidence(),
                basis,
                analysis.confidence() < 0.7
        );
    }
}
