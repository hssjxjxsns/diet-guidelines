package com.dietguide.service;

import com.dietguide.domain.PlanItem;
import com.dietguide.domain.ReplaceConstraints;
import com.dietguide.domain.ValidationResult;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface PlanSessionStore {

    /** 新建会话：写 plan_items（当前方案）+ plan_revisions revision=1。返回 sessionId。 */
    String save(PlanSessionDraft draft);

    Map<String, Object> find(String userId, String sessionId);

    /** 仅表示"结束规划"：状态置 closed，不再参与记录（记录走 acceptPlan/commitDraft）。 */
    void complete(String userId, String sessionId);

    List<PlanItem> currentItems(String userId, String sessionId);

    int currentRevision(String userId, String sessionId);

    /** 换菜版本化：保存新 revision（含当前方案/反馈/约束/校验），并替换当前 plan_items。返回新 revision。 */
    int saveRevision(RevisionDraft draft);

    void markAccepting(String userId, String sessionId);

    void markLogged(String userId, String sessionId, String groupId);

    /** 失败迁移（独立于记录事务，回滚后仍保留 FAILED 痕迹）。 */
    void markFailed(String userId, String sessionId);

    record RevisionDraft(
            String userId,
            String sessionId,
            Integer baseRevision,
            String source,
            List<PlanItem> items,
            ReplaceConstraints constraints,
            String userFeedback,
            ValidationResult validationResult
    ) {
    }

    record PlanSessionDraft(
            String userId,
            LocalDate mealDate,
            String mealType,
            String status,
            Map<String, Object> contextSnapshot,
            ValidationResult validationResult,
            List<PlanItem> items,
            String source
    ) {
    }
}
