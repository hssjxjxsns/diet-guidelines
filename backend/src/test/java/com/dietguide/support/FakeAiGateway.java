package com.dietguide.support;

import com.dietguide.ai.AiGateway;
import com.dietguide.domain.DishAnalysis;
import com.dietguide.domain.MealPlanDraft;
import com.dietguide.shared.AppException;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public class FakeAiGateway implements AiGateway {

    private final Deque<MealPlanDraft> plans = new ArrayDeque<>();
    private final Deque<DishAnalysis> dishes = new ArrayDeque<>();

    public FakeAiGateway enqueuePlan(MealPlanDraft draft) {
        plans.add(draft);
        return this;
    }

    public FakeAiGateway enqueueDish(DishAnalysis analysis) {
        dishes.add(analysis);
        return this;
    }

    @Override
    public <T> T chatJson(ChatRequest request, Class<T> type) {
        if (type == MealPlanDraft.class) {
            MealPlanDraft draft = plans.poll();
            if (draft == null) {
                throw AppException.aiUnavailable("no queued plan");
            }
            return type.cast(draft);
        }
        if (type == DishAnalysis.class) {
            DishAnalysis dish = dishes.poll();
            if (dish == null) {
                throw AppException.aiUnavailable("no queued dish");
            }
            return type.cast(dish);
        }
        throw AppException.aiUnavailable("unsupported response type: " + type.getSimpleName());
    }

    @Override
    public List<double[]> embed(List<String> texts) {
        return List.of();
    }
}
