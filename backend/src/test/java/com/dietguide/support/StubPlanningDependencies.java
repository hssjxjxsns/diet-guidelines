package com.dietguide.support;

import com.dietguide.domain.GuidelineChunk;
import com.dietguide.domain.InferredTrend;
import com.dietguide.domain.PlanningContext;
import com.dietguide.domain.RecentTrend;
import com.dietguide.domain.UserSettings;
import com.dietguide.nutrition.StructureEvaluator;
import com.dietguide.nutrition.Targets;
import com.dietguide.service.GuidelineRetriever;
import com.dietguide.service.PlanningContextLoader;
import com.dietguide.shared.Nutrients;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public class StubPlanningDependencies {

    public static PlanningContextLoader contextLoader(UserSettings settings) {
        return (userId, mealDate, mealType) -> new PlanningContext.UserContext(
                settings,
                settings.dailyTargets(),
                Nutrients.empty(),
                Targets.ALL_MEALS,
                StructureEvaluator.evaluateStructure(Map.of(), mealType, 0),
                List.<RecentTrend>of(),
                List.<InferredTrend>of()
        );
    }

    public static GuidelineRetriever guidelineRetriever() {
        return (query, limit) -> List.of(new GuidelineChunk(
                "g-1",
                "餐餐有蔬菜，保证每天摄入不少于 300g 的新鲜蔬菜。",
                Map.of("source", "中国居民膳食指南2022"),
                0.9
        ));
    }

    private StubPlanningDependencies() {
    }
}
