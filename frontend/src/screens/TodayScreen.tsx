import { useCallback, useEffect, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import {
  MEAL_LABELS,
  MEAL_ORDER,
  api,
  mealTypeForHour,
  newIdempotencyKey,
  shiftDate,
  today,
  weekStart
} from '../api/client';
import type {
  DaySummary,
  MealEntryItem,
  MealGroup,
  MealType,
  Nutrients,
  PeriodReport,
  PlanResult
} from '../api/types';
import { Button, ProgressBar, ViolationBanner } from '../ui/common';
import { colors, common, spacing } from '../theme';

export interface EditTarget {
  group: MealGroup;
  items: MealEntryItem[];
}

interface Props {
  date: string;
  onChangeDate: (date: string) => void;
  onAdd: (mealType: MealType) => void;
  onEdit: (target: EditTarget) => void;
  onRefreshRequest: () => void;
  refreshToken: number;
}

export function TodayScreen(props: Props) {
  const { date, onChangeDate, onAdd, onEdit, onRefreshRequest, refreshToken } = props;

  const [summary, setSummary] = useState<DaySummary | null>(null);
  const [targets, setTargets] = useState<Nutrients>({});
  const [week, setWeek] = useState<PeriodReport | null>(null);
  const [now, setNow] = useState(() => new Date());
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [violations, setViolations] = useState<string[]>([]);

  const [plan, setPlan] = useState<PlanResult | null>(null);
  const [planMealType, setPlanMealType] = useState<MealType>('dinner');
  const [planBusy, setPlanBusy] = useState(false);
  const [planError, setPlanError] = useState<string | null>(null);

  useEffect(() => {
    const timer = setInterval(() => setNow(new Date()), 30_000);
    return () => clearInterval(timer);
  }, []);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [day, settings, report] = await Promise.all([
        api.getDay(date),
        api.getSettings().catch(() => null),
        api.getReport('weekly', weekStart(date)).catch(() => null)
      ]);
      setSummary(day);
      setTargets(settings?.dailyTargets ?? {});
      setWeek(report);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [date]);

  useEffect(() => {
    void load();
  }, [load, refreshToken]);

  const groups = [...(summary?.groups ?? [])]
    .filter((item) => item.group.status !== 'abandoned')
    .sort(
      (left, right) =>
        MEAL_ORDER.indexOf(left.group.mealType) - MEAL_ORDER.indexOf(right.group.mealType) ||
        left.group.instanceSeq - right.group.instanceSeq
    );

  const loggedMeals = new Set(groups.filter((item) => item.group.status === 'logged').map((item) => item.group.mealType));
  const nextMeal = mealTypeForHour(now.getHours());
  const energy = summary?.dailyTotals.energyKcal ?? 0;
  const energyTarget = targets.energyKcal ?? 0;

  const timeline = groups
    .flatMap((item) =>
      item.effective.map((entry) => ({
        key: entry.id,
        time: formatTime(entry.createdAt),
        label: MEAL_LABELS[item.group.mealType],
        energy: entry.totals.energyKcal ?? 0,
        names: entry.items.map((food) => `${food.rawName} ${Math.round(food.amountG)}g`).join('、')
      }))
    )
    .sort((left, right) => left.time.localeCompare(right.time));

  async function generatePlan() {
    setPlanBusy(true);
    setPlanError(null);
    setViolations([]);
    try {
      const result = await api.createPlan(date, nextMeal);
      setPlan(result);
      setPlanMealType(nextMeal);
    } catch (requestError) {
      setPlanError(requestError instanceof Error ? requestError.message : 'AI 配餐失败');
    } finally {
      setPlanBusy(false);
    }
  }

  async function revisePlan() {
    if (!plan) {
      return;
    }
    setPlanBusy(true);
    setPlanError(null);
    try {
      // 版本化换菜：同会话新 revision，反馈「换一批」入修订历史
      setPlan(await api.revisePlan(plan.sessionId, plan.items, {}, '换一批'));
    } catch (requestError) {
      setPlanError(requestError instanceof Error ? requestError.message : '换菜失败');
    } finally {
      setPlanBusy(false);
    }
  }

  /** 原子接受配餐：单接口完成会话校验→记录→幂等→状态迁移（不再前端拼 append）。 */
  async function acceptPlan() {
    if (!plan || plan.items.length === 0) {
      return;
    }
    setPlanBusy(true);
    setPlanError(null);
    setViolations([]);
    try {
      const result = await api.acceptPlan(plan.sessionId, true, newIdempotencyKey());
      setPlan(null);
      if (result.status !== 'LOGGED') {
        setPlanError('方案已生成草稿，请在对应餐次中确认提交');
      }
      onRefreshRequest();
    } catch (requestError) {
      const apiError = requestError as { message?: string; details?: unknown };
      const details = apiError.details;
      const messages = Array.isArray(details)
        ? (details as Array<{ message?: string }>).map((rule) => rule.message ?? String(rule))
        : [];
      if (messages.length > 0) {
        setViolations(messages);
      } else {
        setPlanError(apiError.message ?? '记入失败');
      }
    } finally {
      setPlanBusy(false);
    }
  }

  return (
    <ScrollView style={common.screen} contentContainerStyle={styles.page}>
      <View style={styles.header}>
        <View>
          <Text style={styles.greeting}>{greeting(now.getHours())}</Text>
          <Text style={styles.clock}>{formatClock(now)}</Text>
        </View>
        <View style={styles.dateNav}>
          <Pressable style={styles.arrowButton} onPress={() => onChangeDate(shiftDate(date, -1))}>
            <Text style={styles.arrow}>‹</Text>
          </Pressable>
          <Text style={styles.dateText}>{date === today() ? '今天' : date}</Text>
          <Pressable style={styles.arrowButton} onPress={() => onChangeDate(shiftDate(date, 1))}>
            <Text style={styles.arrow}>›</Text>
          </Pressable>
        </View>
      </View>

      {error ? <Text style={common.error}>{error}</Text> : null}

      <View style={styles.energyCard}>
        <View style={styles.energyTop}>
          <View>
            <Text style={styles.energyLabel}>今日已摄入</Text>
            <Text style={styles.energyValue}>{Math.round(energy)}</Text>
            <Text style={styles.energyUnit}>kcal</Text>
          </View>
          <View style={styles.energyRight}>
            <Text style={styles.energyLabel}>目标</Text>
            <Text style={styles.energyTarget}>
              {energyTarget > 0 ? Math.round(energyTarget) : '未设置'}
            </Text>
            <Text style={styles.energyUnit}>kcal</Text>
          </View>
        </View>
        <ProgressBar
          ratio={energyTarget > 0 ? energy / energyTarget : 0}
          over={energy > energyTarget}
          light
        />
        <Text style={styles.energyHint}>
          {energyTarget <= 0
            ? '去「设置」填身体数据，自动算出你的目标'
            : energy > energyTarget
              ? `超出 ${Math.round(energy - energyTarget)} kcal`
              : `还可摄入 ${Math.round(energyTarget - energy)} kcal`}
        </Text>
        <View style={styles.macroRow}>
          <Macro label="蛋白质" value={summary?.dailyTotals.proteinG} target={targets.proteinG} />
          <Macro label="脂肪" value={summary?.dailyTotals.fatG} target={targets.fatG} />
          <Macro label="碳水" value={summary?.dailyTotals.carbG} target={targets.carbG} />
        </View>
        {summary?.structure?.foodGroups?.length ? (
          <Text style={styles.structureHint}>
            结构：{summary.structure.foodGroups
              .filter((group) => 'vegetables' === group.group || 'protein' === group.group)
              .map((group) => `${groupLabel(group.group)} ${Math.round(group.amountG)}g`)
              .join(' · ')}
          </Text>
        ) : null}
      </View>

      <View style={styles.aiCard}>
        <View style={styles.aiHeader}>
          <Text style={styles.aiTitle}>AI 建议 · {MEAL_LABELS[nextMeal]}</Text>
          <Text style={styles.aiTime}>{formatClock(now)}</Text>
        </View>
        <Text style={styles.aiSub}>
          {loggedMeals.size === 0
            ? '今天还没记录，先让 AI 帮你安排一餐'
            : `今天已记录 ${loggedMeals.size} 餐，${loggedMeals.has(nextMeal) ? '这一餐也已记录' : `该吃${MEAL_LABELS[nextMeal]}了`}`}
        </Text>

        {plan ? (
          <>
            {plan.items.map((item) => (
              <View key={`${item.foodId}-${item.name}`} style={styles.planItem}>
                <Text style={styles.planItemName}>{item.name}</Text>
                <Text style={styles.planItemAmount}>{Math.round(item.amountG)} g</Text>
              </View>
            ))}
            {plan.explanation ? <Text style={styles.aiSub}>{plan.explanation}</Text> : null}
            {plan.source ? (
              <Text style={styles.aiCitation}>
                方案来源：{plan.source.startsWith('RULE_FALLBACK') ? '规则引擎兜底' : 'AI'} ·
                版本 {plan.source.split(':')[1] ?? '1'}
              </Text>
            ) : null}
            {plan.guidelines?.length ? (
              <Text style={styles.aiCitation}>
                依据：{plan.guidelines.map((item) => item.title).join('、')}
              </Text>
            ) : null}
            {plan.gap?.remaining?.energyKcal !== undefined ? (
              <Text style={styles.aiSub}>
                全天缺口提示：今日还可摄入约 {Math.round(plan.gap.remaining.energyKcal)} kcal（本餐按本餐目标设计）
              </Text>
            ) : null}
            {plan.validation?.failedRules?.length ? (
              <ViolationBanner
                messages={plan.validation.failedRules
                  .filter((rule) => rule.severity === 'hard')
                  .map((rule) => rule.message ?? String(rule.ruleId))}
              />
            ) : null}
            <View style={styles.planActions}>
              <Button label="换一批" variant="ghost" onPress={() => void revisePlan()} disabled={planBusy} />
              <Button
                label={`记入${MEAL_LABELS[planMealType]}`}
                onPress={() => void acceptPlan()}
                disabled={planBusy}
              />
            </View>
          </>
        ) : (
          <Button
            label={planBusy ? 'AI 正在配餐…' : `让 AI 安排${MEAL_LABELS[nextMeal]}`}
            onPress={() => void generatePlan()}
            disabled={planBusy}
          />
        )}
        <ViolationBanner messages={violations} />
        {planError ? <Text style={common.error}>{planError}</Text> : null}
      </View>

      <View style={styles.sectionCard}>
        <Text style={styles.sectionTitle}>今日餐食</Text>
        {timeline.length === 0 ? (
          <Text style={common.meta}>还没有记录，点下面各餐的「添加」或直接用上面的 AI 建议。</Text>
        ) : (
          timeline.map((item) => (
            <View key={item.key} style={styles.timelineRow}>
              <View style={styles.timelineDot} />
              <View style={styles.timelineBody}>
                <View style={styles.timelineTop}>
                  <Text style={styles.timelineLabel}>
                    {item.time} · {item.label}
                  </Text>
                  <Text style={styles.timelineEnergy}>{Math.round(item.energy)} kcal</Text>
                </View>
                <Text style={styles.timelineNames}>{item.names}</Text>
              </View>
            </View>
          ))
        )}
      </View>

      <View style={styles.sectionCard}>
        <View style={styles.weekHeader}>
          <Text style={styles.sectionTitle}>本周概况</Text>
          <Text style={common.meta}>{week ? `${week.periodStart} ~ ${week.periodEnd}` : ''}</Text>
        </View>
        {week ? (
          <View style={styles.weekBlock}>
            <View style={styles.weekRow}>
              <WeekMetric label="平均能量" value={`${Math.round(week.stats.average.energyKcal ?? 0)}`} unit="kcal" />
              <WeekMetric label="记录天数" value={`${week.stats.days}`} unit="天" />
              <WeekMetric label="平均蛋白" value={`${Math.round(week.stats.average.proteinG ?? 0)}`} unit="g" />
            </View>
            {week.stats.foodGroups?.length ? (
              <View style={styles.weekBadges}>
                {week.stats.foodGroups
                  .filter((group) => ['vegetables', 'protein', 'grains'].includes(group.group))
                  .map((group) => (
                    <View
                      key={group.group}
                      style={[styles.weekBadge, group.status !== 'adequate' && styles.weekBadgeBad]}
                    >
                      <Text style={styles.weekBadgeText}>
                        {groupLabel(group.group)} {Math.round(group.adherenceRate * 100)}%
                      </Text>
                    </View>
                  ))}
              </View>
            ) : null}
          </View>
        ) : (
          <Text style={common.meta}>{loading ? '加载中…' : '暂无数据'}</Text>
        )}
      </View>

      {MEAL_ORDER.map((mealType) => {
        const mealGroups = groups.filter((item) => item.group.mealType === mealType);
        if (mealGroups.length === 0) {
          return (
            <View key={mealType} style={styles.sectionCard}>
              <View style={styles.mealHeader}>
                <Text style={styles.sectionTitle}>{MEAL_LABELS[mealType]}</Text>
                <Button label="添加" variant="soft" onPress={() => onAdd(mealType)} />
              </View>
              <Text style={common.meta}>未记录</Text>
            </View>
          );
        }
        return mealGroups.map((item) => {
          const isDraft = item.group.status === 'draft';
          const items = [
            ...item.effective.flatMap((entry) => entry.items),
            ...item.draftItems.map((draft) => ({
              foodId: draft.foodId ?? undefined,
              rawName: draft.rawName,
              amountG: draft.amountG,
              valueType: draft.valueType as MealEntryItem['valueType'],
              nutrients: {}
            }))
          ];
          const mealEnergy = item.effective.reduce((sum, entry) => sum + (entry.totals.energyKcal ?? 0), 0);
          const hasPending = item.draftItems.some((draft) => draft.confirmationStatus !== 'confirmed');
          return (
            <View key={item.group.id} style={styles.sectionCard}>
              <View style={styles.mealHeader}>
                <Text style={styles.sectionTitle}>
                  {MEAL_LABELS[mealType]}
                  {isMultiInstance(mealType) ? ` #${item.group.instanceSeq}` : ''}
                  {isDraft ? '（草稿）' : ''}
                </Text>
                <View style={styles.mealActions}>
                  {isDraft && hasPending ? <Text style={styles.pendingTag}>有待确认项</Text> : null}
                  <Button
                    label={isDraft ? '继续编辑' : '修正'}
                    variant={isDraft ? 'primary' : 'ghost'}
                    onPress={() =>
                      onEdit({
                        group: item.group,
                        items: item.effective.flatMap((entry) => entry.items)
                      })
                    }
                  />
                </View>
              </View>
              {items.map((food, index) => (
                <View key={`${item.group.id}-${index}`} style={styles.foodRow}>
                  <Text style={styles.foodName}>{food.rawName}</Text>
                  <Text style={common.meta}>{Math.round(food.amountG)} g</Text>
                </View>
              ))}
              {mealEnergy > 0 ? (
                <Text style={styles.mealTotal}>合计 {Math.round(mealEnergy)} kcal</Text>
              ) : null}
            </View>
          );
        });
      })}
    </ScrollView>
  );
}

function isMultiInstance(mealType: MealType): boolean {
  return mealType === 'snack' || mealType === 'night_snack';
}

const GROUP_LABELS: Record<string, string> = {
  vegetables: '蔬菜',
  protein: '蛋白',
  grains: '主食',
  fruits: '水果',
  dairy: '奶类',
  oils: '油盐'
};

function groupLabel(group: string): string {
  return GROUP_LABELS[group] ?? group;
}

function Macro(props: { label: string; value?: number; target?: number }) {
  const value = props.value ?? 0;
  const ratio = props.target ? Math.min(value / props.target, 1.5) : 0;
  return (
    <View style={styles.macro}>
      <Text style={styles.macroLabel}>{props.label}</Text>
      <Text style={styles.macroValue}>{Math.round(value)} g</Text>
      <ProgressBar ratio={ratio} />
      <Text style={styles.macroTarget}>
        {props.target ? `${Math.round((value / props.target) * 100)}%` : '-'}
      </Text>
    </View>
  );
}

function WeekMetric(props: { label: string; value: string; unit: string }) {
  return (
    <View style={styles.weekMetric}>
      <Text style={styles.macroLabel}>{props.label}</Text>
      <Text style={styles.macroValue}>
        {props.value}
        <Text style={styles.weekUnit}> {props.unit}</Text>
      </Text>
    </View>
  );
}

function greeting(hour: number): string {
  if (hour < 6) {
    return '夜深了';
  }
  if (hour < 11) {
    return '早上好';
  }
  if (hour < 14) {
    return '中午好';
  }
  if (hour < 18) {
    return '下午好';
  }
  return '晚上好';
}

function formatClock(date: Date): string {
  const hour = date.getHours().toString().padStart(2, '0');
  const minute = date.getMinutes().toString().padStart(2, '0');
  return `${hour}:${minute}`;
}

function formatTime(value: string): string {
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) {
    return '--:--';
  }
  return formatClock(parsed);
}

const styles = StyleSheet.create({
  page: { padding: spacing.lg, gap: spacing.md, paddingBottom: 32 },
  header: {
    flexDirection: 'row',
    alignItems: 'flex-end',
    justifyContent: 'space-between',
    marginBottom: spacing.xs
  },
  greeting: { fontSize: 22, fontWeight: '700', color: colors.text },
  clock: { fontSize: 13, color: colors.muted, marginTop: 2 },
  dateNav: { flexDirection: 'row', alignItems: 'center', gap: spacing.xs },
  arrowButton: {
    width: 32,
    height: 32,
    borderRadius: 16,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.surface
  },
  arrow: { fontSize: 18, color: colors.text, lineHeight: 20 },
  dateText: { fontSize: 13, color: colors.muted, minWidth: 68, textAlign: 'center' },
  energyCard: {
    backgroundColor: colors.primary,
    borderRadius: 18,
    padding: spacing.lg,
    gap: spacing.sm
  },
  energyTop: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-end' },
  energyLabel: { color: 'rgba(255,255,255,0.75)', fontSize: 12 },
  energyValue: { color: '#ffffff', fontSize: 38, fontWeight: '700', lineHeight: 42 },
  energyTarget: { color: '#ffffff', fontSize: 22, fontWeight: '600', textAlign: 'right' },
  energyUnit: { color: 'rgba(255,255,255,0.75)', fontSize: 12 },
  energyRight: { alignItems: 'flex-end' },
  energyHint: { color: 'rgba(255,255,255,0.85)', fontSize: 12 },
  structureHint: { color: 'rgba(255,255,255,0.9)', fontSize: 12 },
  macroRow: { flexDirection: 'row', gap: spacing.md, marginTop: spacing.xs },
  macro: { flex: 1, gap: 4 },
  macroLabel: { fontSize: 12, color: colors.muted },
  macroValue: { fontSize: 16, fontWeight: '600', color: colors.text },
  macroTarget: { fontSize: 11, color: colors.muted },
  aiCard: {
    backgroundColor: '#eef7f1',
    borderRadius: 18,
    padding: spacing.lg,
    gap: spacing.sm
  },
  aiHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  aiTitle: { fontSize: 16, fontWeight: '700', color: colors.primary },
  aiTime: { fontSize: 12, color: colors.muted },
  aiSub: { fontSize: 13, color: colors.muted, lineHeight: 19 },
  aiCitation: { fontSize: 12, color: colors.primary, lineHeight: 18 },
  planItem: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingVertical: 6,
    borderBottomWidth: 1,
    borderBottomColor: 'rgba(47,133,90,0.12)'
  },
  planItemName: { fontSize: 15, color: colors.text },
  planItemAmount: { fontSize: 14, color: colors.muted },
  planActions: { flexDirection: 'row', justifyContent: 'flex-end', gap: spacing.sm },
  sectionCard: {
    backgroundColor: colors.surface,
    borderRadius: 16,
    padding: spacing.lg,
    gap: spacing.sm
  },
  sectionTitle: { fontSize: 16, fontWeight: '700', color: colors.text },
  timelineRow: { flexDirection: 'row', gap: spacing.md, paddingVertical: 6 },
  timelineDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    backgroundColor: colors.primary,
    marginTop: 6
  },
  timelineBody: { flex: 1, gap: 2 },
  timelineTop: { flexDirection: 'row', justifyContent: 'space-between' },
  timelineLabel: { fontSize: 13, color: colors.muted },
  timelineEnergy: { fontSize: 13, color: colors.primary, fontWeight: '600' },
  timelineNames: { fontSize: 14, color: colors.text },
  weekHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  weekBlock: { gap: spacing.sm },
  weekRow: { flexDirection: 'row', gap: spacing.md },
  weekBadges: { flexDirection: 'row', gap: spacing.sm, flexWrap: 'wrap' },
  weekBadge: {
    backgroundColor: colors.primarySoft,
    borderRadius: 8,
    paddingHorizontal: spacing.sm,
    paddingVertical: 3
  },
  weekBadgeBad: { backgroundColor: '#fdeeec' },
  weekBadgeText: { fontSize: 12, color: colors.text },
  weekMetric: { flex: 1, gap: 2 },
  weekUnit: { fontSize: 12, color: colors.muted, fontWeight: '400' },
  mealHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  mealActions: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  pendingTag: { fontSize: 11, color: colors.warning },
  foodRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingVertical: 4,
    borderBottomWidth: 1,
    borderBottomColor: '#f2f4f7'
  },
  foodName: { fontSize: 14, color: colors.text },
  mealTotal: { fontSize: 13, color: colors.primary, fontWeight: '600', marginTop: 2 }
});
