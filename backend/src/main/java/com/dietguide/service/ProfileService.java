package com.dietguide.service;

import com.dietguide.domain.InferredTrend;
import com.dietguide.domain.UserSettings;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProfileService {

    private final ProfileStore store;

    public ProfileService(ProfileStore store) {
        this.store = store;
    }

    public UserSettings getSettings(String userId) {
        return store.getSettings(userId);
    }

    /** 用户明确设置：只能通过用户操作写入，AI 流程不得调用此方法。 */
    public void updateSettings(String userId, UserSettings settings) {
        store.saveSettings(userId, settings);
    }

    /**
     * 刷新推断趋势：新结论写入；不再成立的旧结论置为 invalidated。
     */
    public void refreshTrends(String userId, List<InferredTrend> next, List<String> checkedMetrics) {
        List<InferredTrend> existing = store.listTrends(userId);
        List<InferredTrend> nextValid = next.stream().filter(trend -> trend != null).toList();

        for (InferredTrend trend : nextValid) {
            store.saveTrend(userId, trend);
        }

        for (String metric : checkedMetrics) {
            boolean stillValid = nextValid.stream().anyMatch(trend -> trend.metric().equals(metric));
            boolean existed = existing.stream()
                    .anyMatch(trend -> trend.metric().equals(metric) && !"invalidated".equals(trend.status()));
            if (!stillValid && existed) {
                store.invalidateTrend(userId, metric);
            }
        }
    }
}
