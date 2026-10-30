import { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, ScrollView, StyleSheet, Text, View } from 'react-native';
import { api, shiftDate, today, weekStart } from '../api/client';
import type { FoodGroupAdherence, PeriodReport } from '../api/types';
import { Chip, ProgressBar } from '../ui/common';
import { colors, common, spacing } from '../theme';

type PeriodType = 'weekly' | 'monthly';

const GROUP_LABELS: Record<string, string> = {
  vegetables: '蔬菜',
  protein: '蛋白质类',
  grains: '谷物主食',
  fruits: '水果',
  dairy: '奶类',
  oils: '油盐'
};

/** 报表以膳食指南结构（食物组达标）为主指标，营养加总为辅；AI 总结只是解释层。 */
export function ReportsScreen() {
  const [periodType, setPeriodType] = useState<PeriodType>('weekly');
  const [offset, setOffset] = useState(0);
  const [report, setReport] = useState<PeriodReport | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const start = useCallback(() => {
    if (periodType === 'weekly') {
      return shiftDate(weekStart(today()), offset * 7);
    }
    const base = new Date(`${today().slice(0, 7)}-01T00:00:00`);
    base.setMonth(base.getMonth() + offset);
    return `${base.getFullYear()}-${String(base.getMonth() + 1).padStart(2, '0')}-01`;
  }, [periodType, offset]);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setReport(await api.getReport(periodType, start()));
    } catch (requestError) {
      setReport(null);
      setError(requestError instanceof Error ? requestError.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [periodType, start]);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <ScrollView style={common.screen} contentContainerStyle={common.page}>
      <View style={styles.switchRow}>
        <Chip
          label="本周"
          active={periodType === 'weekly'}
          onPress={() => {
            setPeriodType('weekly');
            setOffset(0);
          }}
        />
        <Chip
          label="本月"
          active={periodType === 'monthly'}
          onPress={() => {
            setPeriodType('monthly');
            setOffset(0);
          }}
        />
      </View>

      <View style={styles.switchRow}>
        <Chip label="上一期" onPress={() => setOffset((value) => value - 1)} />
        <Text style={common.meta}>
          {report ? `${report.periodStart} ~ ${report.periodEnd}` : start()}
        </Text>
        <Chip
          label="下一期"
          onPress={() => setOffset((value) => Math.min(value + 1, 0))}
        />
      </View>

      {loading ? <ActivityIndicator color={colors.primary} /> : null}
      {error ? <Text style={common.error}>{error}</Text> : null}

      {report ? (
        <>
          {report.stats.foodGroups && report.stats.foodGroups.length > 0 ? (
            <View style={common.card}>
              <Text style={common.label}>食物组达标（膳食指南 · 主指标）</Text>
              {report.stats.foodGroups.map((group) => (
                <FoodGroupRow key={group.group} group={group} />
              ))}
            </View>
          ) : (
            <View style={common.card}>
              <Text style={common.meta}>本期暂无结构数据（记录后自动生成）</Text>
            </View>
          )}

          {report.stats.guideline ? (
            <View style={common.card}>
              <Text style={common.label}>指南符合度</Text>
              <View style={styles.guidelineRow}>
                <GuidelineMetric
                  label="结构数据完整率"
                  value={`${Math.round((report.stats.guideline.structureNonEmptyRate ?? 0) * 100)}%`}
                />
                <GuidelineMetric
                  label="多样性达标天数"
                  value={`${report.stats.guideline.diversityOkDays ?? 0} 天`}
                />
                <GuidelineMetric
                  label="油盐限量达标"
                  value={`${report.stats.guideline.oilWithinLimitDays ?? 0} 天`}
                />
              </View>
              <Text style={common.meta}>
                钠日均 {Math.round(report.stats.guideline.sodiumAverageMg ?? 0)} mg ·{' '}
                {report.stats.guideline.sodiumWithinLimit ? '低于 2000mg 上限' : '超过 2000mg 上限'}
              </Text>
              {report.stats.guideline.conclusion ? (
                <Text style={common.meta}>{report.stats.guideline.conclusion}</Text>
              ) : null}
            </View>
          ) : null}

          <View style={common.card}>
            <Text style={common.label}>平均每日摄入（共 {report.stats.days} 天 · 辅指标）</Text>
            <Text style={common.value}>
              {Math.round(report.stats.average.energyKcal ?? 0)} kcal
            </Text>
            <View style={styles.nutrientRow}>
              <Metric label="蛋白质" value={report.stats.average.proteinG} />
              <Metric label="脂肪" value={report.stats.average.fatG} />
              <Metric label="碳水" value={report.stats.average.carbG} />
            </View>
          </View>

          <View style={common.card}>
            <Text style={common.label}>区间合计</Text>
            <Text style={styles.total}>
              {Math.round(report.stats.total.energyKcal ?? 0)} kcal
            </Text>
            <Text style={common.meta}>
              蛋白质 {Math.round(report.stats.total.proteinG ?? 0)} g · 脂肪{' '}
              {Math.round(report.stats.total.fatG ?? 0)} g · 碳水{' '}
              {Math.round(report.stats.total.carbG ?? 0)} g
            </Text>
          </View>

          {report.aiSummary ? (
            <View style={common.card}>
              <Text style={common.label}>AI 小结（解释层）</Text>
              <Text style={styles.summary}>{report.aiSummary}</Text>
            </View>
          ) : (
            <Text style={common.meta}>AI 小结暂不可用，以上统计均为确定性计算结果。</Text>
          )}
        </>
      ) : null}
    </ScrollView>
  );
}

function FoodGroupRow(props: { group: FoodGroupAdherence }) {
  const { group } = props;
  const color = group.status === 'adequate' ? colors.primary : colors.danger;
  return (
    <View style={styles.groupRow}>
      <View style={styles.groupTop}>
        <Text style={styles.groupName}>{GROUP_LABELS[group.group] ?? group.group}</Text>
        <Text style={[styles.groupStatus, { color }]}>
          {group.status === 'adequate' ? '达标' : group.status === 'insufficient' ? '不足' : '过量'} ·{' '}
          {Math.round(group.adherenceRate * 100)}%
        </Text>
      </View>
      <ProgressBar ratio={group.adherenceRate} over={group.status === 'excessive'} />
      <Text style={common.meta}>
        日均 {Math.round(group.averageG)} g · 建议 {Math.round(group.recommendedRangeG[0])}–
        {Math.round(group.recommendedRangeG[1])} g
      </Text>
    </View>
  );
}

function GuidelineMetric(props: { label: string; value: string }) {
  return (
    <View style={styles.metric}>
      <Text style={common.label}>{props.label}</Text>
      <Text style={styles.metricValue}>{props.value}</Text>
    </View>
  );
}

function Metric(props: { label: string; value?: number }) {
  return (
    <View style={styles.metric}>
      <Text style={common.label}>{props.label}</Text>
      <Text style={styles.metricValue}>{Math.round(props.value ?? 0)} g</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  switchRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: spacing.sm
  },
  groupRow: { gap: 4, paddingVertical: 6 },
  groupTop: { flexDirection: 'row', justifyContent: 'space-between' },
  groupName: { fontSize: 14, color: colors.text, fontWeight: '600' },
  groupStatus: { fontSize: 12, fontWeight: '600' },
  guidelineRow: { flexDirection: 'row', gap: spacing.md, marginTop: spacing.xs },
  nutrientRow: { flexDirection: 'row', gap: spacing.md, marginTop: spacing.sm },
  metric: { flex: 1, gap: 2 },
  metricValue: { fontSize: 16, fontWeight: '600', color: colors.text },
  total: { fontSize: 20, fontWeight: '600', color: colors.text },
  summary: { fontSize: 14, lineHeight: 20, color: colors.text }
});
