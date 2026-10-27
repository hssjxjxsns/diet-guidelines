package com.dietguide.support;

import com.dietguide.service.PlanSessionStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MemoryPlanSessionStore implements PlanSessionStore {

    private final List<PlanSessionDraft> drafts = new ArrayList<>();

    @Override
    public String save(PlanSessionDraft draft) {
        drafts.add(draft);
        return "session-" + drafts.size();
    }

    @Override
    public Map<String, Object> find(String userId, String sessionId) {
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("id", sessionId);
        session.put("user_id", userId);
        if (drafts.isEmpty()) {
            return session;
        }
        PlanSessionDraft draft = drafts.get(drafts.size() - 1);
        session.put("meal_date", draft.mealDate().toString());
        session.put("meal_type", draft.mealType());
        session.put("status", draft.status());
        return session;
    }

    @Override
    public void complete(String userId, String sessionId) {
        // no-op for tests
    }

    public List<PlanSessionDraft> drafts() {
        return drafts;
    }
}
