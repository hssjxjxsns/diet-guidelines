import { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, ScrollView, StyleSheet, Text, View } from 'react-native';
import { api, shiftDate, today, weekStart } from '../api/client';
import type { PeriodReport } from '../api/types';
import { Chip } from '../ui/common';
import { colors, common, spacing } from '../theme';

type PeriodType = 'weekly' | 'monthly';

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
          <View style={common.card}>
            <Text style={common.label}>平均每日摄入（共 {report.stats.days} 天）</Text>
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
              <Text style={common.label}>AI 小结</Text>
              <Text style={styles.summary}>{report.aiSummary}</Text>
            </View>
          ) : null}
        </>
      ) : null}
    </ScrollView>
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
  nutrientRow: { flexDirection: 'row', gap: spacing.md, marginTop: spacing.sm },
  metric: { flex: 1, gap: 2 },
  metricValue: { fontSize: 16, fontWeight: '600', color: colors.text },
  total: { fontSize: 20, fontWeight: '600', color: colors.text },
  summary: { fontSize: 14, lineHeight: 20, color: colors.text }
});
