package com.dietguide.support;

import com.dietguide.domain.PlanItem;
import com.dietguide.service.PlanSessionStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MemoryPlanSessionStore implements PlanSessionStore {

    private final List<PlanSessionDraft> drafts = new ArrayList<>();
    private final Map<String, List<PlanItem>> currentItems = new LinkedHashMap<>();
    private final Map<String, Integer> revisions = new LinkedHashMap<>();
    private final Map<String, List<RevisionDraft>> revisionHistory = new LinkedHashMap<>();
    private final Map<String, String> statuses = new LinkedHashMap<>();
    private final Map<String, String> acceptedGroups = new LinkedHashMap<>();

    @Override
    public String save(PlanSessionDraft draft) {
        drafts.add(draft);
        String sessionId = "session-" + drafts.size();
        currentItems.put(sessionId, new ArrayList<>(draft.items() == null ? List.of() : draft.items()));
        revisions.put(sessionId, 1);
        statuses.put(sessionId, draft.status());
        return sessionId;
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
        session.put("status", statuses.getOrDefault(sessionId, draft.status()));
        session.put("current_revision", revisions.getOrDefault(sessionId, 1));
        session.put("accepted_group_id", acceptedGroups.get(sessionId));
        session.put("plan_items", currentItems.getOrDefault(sessionId, List.of()));
        return session;
    }

    @Override
    public void complete(String userId, String sessionId) {
        statuses.put(sessionId, "closed");
    }

    @Override
    public List<PlanItem> currentItems(String userId, String sessionId) {
        return new ArrayList<>(currentItems.getOrDefault(sessionId, List.of()));
    }

    @Override
    public int currentRevision(String userId, String sessionId) {
        return revisions.getOrDefault(sessionId, 0);
    }

    @Override
    public int saveRevision(RevisionDraft draft) {
        int next = currentRevision(draft.userId(), draft.sessionId()) + 1;
        revisions.put(draft.sessionId(), next);
        revisionHistory.computeIfAbsent(draft.sessionId(), key -> new ArrayList<>()).add(draft);
        currentItems.put(draft.sessionId(), new ArrayList<>(draft.items()));
        return next;
    }

    @Override
    public void markAccepting(String userId, String sessionId) {
        statuses.put(sessionId, "accepting");
    }

    @Override
    public void markLogged(String userId, String sessionId, String groupId) {
        statuses.put(sessionId, "logged");
        acceptedGroups.put(sessionId, groupId);
    }

    @Override
    public void markFailed(String userId, String sessionId) {
        statuses.put(sessionId, "failed");
    }

    public List<PlanSessionDraft> drafts() {
        return drafts;
    }

    public List<RevisionDraft> revisionHistory(String sessionId) {
        return revisionHistory.getOrDefault(sessionId, List.of());
    }

    public String status(String sessionId) {
        return statuses.get(sessionId);
    }
}
