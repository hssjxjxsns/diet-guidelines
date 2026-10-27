package com.dietguide.web;

import com.dietguide.dish.DishAnalyzer;
import com.dietguide.domain.DishAnalysis;
import com.dietguide.service.RecipeCandidateStore;
import com.dietguide.shared.AppException;
import com.dietguide.web.security.CurrentUserId;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
    public DishAnalysis parse(@CurrentUserId String userId, @RequestBody ParseRequest request) {
        if ((request.text() == null || request.text().isBlank())
                && (request.imageUrl() == null || request.imageUrl().isBlank())) {
            throw AppException.validation("text or image_url is required");
        }

        DishAnalysis analysis = analyzer.analyze(new DishAnalyzer.AnalyzeInput(
                request.text(),
                request.imageUrl(),
                request.hints()
        ));

        if (analysis.recipeProposal() != null) {
            candidates.save(userId, analysis);
        }
        return analysis;
    }
}
