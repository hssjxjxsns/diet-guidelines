import { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { ApiError, MEAL_LABELS, api, newIdempotencyKey } from '../api/client';
import type {
  AiCandidateItem,
  MealDraftItem,
  MealGroup,
  MealType,
  PlanResult
} from '../api/types';
import { AiCandidateSheet, type CandidateSheetState } from './AiCandidateSheet';
import { Button, ItemRow, ViolationBanner } from '../ui/common';
import { colors, common, spacing } from '../theme';

interface Props {
  date: string;
  mealType: MealType;
  /** 修正已记录餐次：带上 instanceSeq 以精确重开该组（其版本链上产生新 revision）。 */
  editInstanceSeq?: number;
  initialNames?: string[];
  onClose: () => void;
  onSubmitted: () => void;
}

/** 记录主流程：开餐（惰性建组）→ item 级草稿编辑 → 规则校验 → commit。 */
export function AddMealScreen(props: Props) {
  const { date, mealType, editInstanceSeq, initialNames, onClose, onSubmitted } = props;

  const [group, setGroup] = useState<MealGroup | null>(null);
  const [items, setItems] = useState<MealDraftItem[]>([]);
  const [query, setQuery] = useState('');
  const [foods, setFoods] = useState<Array<{ id: string; name: string; energyKcal: number }>>([]);
  const [aiText, setAiText] = useState('');
  const [candidate, setCandidate] = useState<CandidateSheetState | null>(null);
  const [plan, setPlan] = useState<PlanResult | null>(null);
  const [violations, setViolations] = useState<string[]>([]);
  const [hint, setHint] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const revisionRef = useRef<number | undefined>(undefined);

  // 开餐：惰性建组/复用草稿/重开已记录组，并载入既有草稿项
  useEffect(() => {
    let cancelled = false;
    async function open() {
      setBusy(true);
      try {
        const draft = await api.openDraft(
          date,
          mealType,
          editInstanceSeq !== undefined && !isMultiInstance(mealType) ? undefined : editInstanceSeq
        );
        if (cancelled) {
          return;
        }
        setGroup(draft);
        revisionRef.current = draft.draftRevision;
        const payload = await api.getGroup(draft.id);
        if (cancelled) {
          return;
        }
        setItems(payload.draftItems);
        if (payload.draftItems.length > 0) {
          setHint('已恢复未提交的草稿');
        }
      } catch (requestError) {
        if (!cancelled) {
          setError(requestError instanceof Error ? requestError.message : '开餐失败');
        }
      } finally {
        if (!cancelled) {
          setBusy(false);
        }
      }
    }
    void open();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [date, mealType, editInstanceSeq]);

  function isMultiInstance(type: MealType): boolean {
    return type === 'snack' || type === 'night_snack';
  }

  // 修正场景：把已记录的菜名展示为提示（用户在草稿上重新编辑产生新 revision）
  useEffect(() => {
    if (initialNames && initialNames.length > 0 && items.length === 0 && group && !busy) {
      let cancelled = false;
      void (async () => {
        for (const name of initialNames) {
          try {
            const added = await api.addDraftItem(group.id, { rawName: name, amountG: 100 });
            if (!cancelled) {
              setItems((previous) => [...previous, added]);
            }
          } catch {
            break;
          }
        }
      })();
      return () => {
        cancelled = true;
      };
    }
    return undefined;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [group?.id]);

  const loadFoods = useCallback(async (keyword: string) => {
    try {
      const list = await api.listFoods(keyword);
      setFoods(list.slice(0, 8).map((food) => ({
        id: food.id,
        name: food.name,
        energyKcal: food.nutrientsPer100g.energyKcal ?? 0
      })));
    } catch {
      setFoods([]);
    }
  }, []);

  useEffect(() => {
    const timer = setTimeout(() => void loadFoods(query), 300);
    return () => clearTimeout(timer);
  }, [query, loadFoods]);

  /** 版本号单一来源：每次成功的写操作使草稿 revision +1（与服务端乐观锁一致）。 */
  function bumpRevision() {
    revisionRef.current = (revisionRef.current ?? 0) + 1;
    if (group) {
      setGroup({ ...group, draftRevision: revisionRef.current });
    }
  }

  async function addFood(food: { id: string; name: string }) {
    if (!group) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const added = await api.addDraftItem(group.id, {
        foodId: food.id,
        rawName: food.name,
        amountG: 100,
        draft_revision: revisionRef.current
      });
      bumpRevision();
      setItems((previous) => [...previous, added]);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : '添加失败');
    } finally {
      setBusy(false);
    }
  }

  async function changeAmount(item: MealDraftItem, amountG: number) {
    if (!group || amountG <= 0) {
      return;
    }
    setItems((previous) =>
      previous.map((row) => (row.id === item.id ? { ...row, amountG } : row))
    );
    try {
      const updated = await api.updateDraftItem(group.id, item.id, {
        amountG,
        draft_revision: revisionRef.current
      });
      bumpRevision();
      setItems((previous) => previous.map((row) => (row.id === item.id ? updated : row)));
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : '修改失败');
    }
  }

  async function removeItem(item: MealDraftItem) {
    if (!group) {
      return;
    }
    setItems((previous) => previous.filter((row) => row.id !== item.id));
    try {
      await api.removeDraftItem(group.id, item.id, revisionRef.current);
      bumpRevision();
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : '删除失败');
    }
  }

  /** AI 估算项确认：confirmation_status → confirmed（幂等）。 */
  async function confirmItem(item: MealDraftItem) {
    if (!group) {
      return;
    }
    try {
      const updated = await api.updateDraftItem(group.id, item.id, {
        confirmationStatus: 'confirmed',
        draft_revision: revisionRef.current
      });
      bumpRevision();
      setItems((previous) => previous.map((row) => (row.id === item.id ? updated : row)));
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : '确认失败');
    }
  }

  async function copyPrevious() {
    if (!group) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const copies = await api.copyPrevious(group.id, revisionRef.current);
      bumpRevision();
      setItems((previous) => [...previous, ...copies.items]);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : '复制上一餐失败');
    } finally {
      setBusy(false);
    }
  }

  async function analyze() {
    if (!aiText.trim()) {
      return;
    }
    setBusy(true);
    setError(null);
    setHint(null);
    try {
      const response = await api.parseDish(aiText.trim());
      setCandidate({
        dishName: response.analysis.dishName ?? aiText.trim(),
        items: response.candidateItems
      });
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : '识别失败');
    } finally {
      setBusy(false);
    }
  }

  /** 候选确认：逐项以 confirmed=true 写入草稿（非 AI 来源的直接落草稿）。 */
  async function acceptCandidates(confirmed: AiCandidateItem[]) {
    if (!group || confirmed.length === 0) {
      setCandidate(null);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const added: MealDraftItem[] = [];
      for (const item of confirmed) {
        added.push(
          await api.addDraftItem(group.id, {
            foodId: item.foodId ?? undefined,
            rawName: item.rawName,
            amountG: item.amountG,
            valueType: item.valueType,
            aiSource: item.aiSource,
            confirmed: true,
            draft_revision: revisionRef.current
          })
        );
        bumpRevision();
      }
      setItems((previous) => [...previous, ...added]);
      setHint(`已确认 ${added.length} 项进入草稿，请核对份量后提交`);
      setCandidate(null);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : '加入草稿失败');
    } finally {
      setBusy(false);
    }
  }

  async function generatePlan() {
    setBusy(true);
    setError(null);
    setHint(null);
    try {
      setPlan(await api.createPlan(date, mealType));
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : 'AI 配餐失败');
    } finally {
      setBusy(false);
    }
  }

  async function revisePlan() {
    if (!plan) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      setPlan(await api.revisePlan(plan.sessionId, plan.items, {}, '换一批'));
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : '换菜失败');
    } finally {
      setBusy(false);
    }
  }

  /** 方案记入草稿：方案项为食物库实测项（measured），逐项加入后统一 commit。 */
  async function applyPlan() {
    if (!group || !plan || plan.items.length === 0) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const added: MealDraftItem[] = [];
      for (const item of plan.items) {
        added.push(
          await api.addDraftItem(group.id, {
            foodId: item.foodId,
            rawName: item.name,
            amountG: item.amountG,
            valueType: 'measured',
            aiSource: `plan-session:${plan.sessionId}`,
            confirmed: true,
            draft_revision: revisionRef.current
          })
        );
        bumpRevision();
      }
      setItems((previous) => [...previous, ...added]);
      setPlan(null);
      setHint('方案已进入草稿，可继续调整后提交');
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : '方案记入失败');
    } finally {
      setBusy(false);
    }
  }

  async function submit() {
    if (!group) {
      return;
    }
    if (items.length === 0) {
      setError('请先添加至少一种食物');
      return;
    }
    if (items.some((item) => item.confirmationStatus !== 'confirmed')) {
      setViolations(['存在待确认的 AI 估算项，请先确认或删除后再提交（R09）']);
      return;
    }
    setBusy(true);
    setError(null);
    setViolations([]);
    try {
      await api.commitDraft(group.id, revisionRef.current, newIdempotencyKey());
      onSubmitted();
    } catch (requestError) {
      if (requestError instanceof ApiError) {
        const details = requestError.details;
        const messages = Array.isArray(details)
          ? (details as Array<{ message?: string }>).map((rule) => rule.message ?? String(rule))
          : [];
        if (messages.length > 0) {
          setViolations(messages);
        } else {
          setError(requestError.message);
        }
      } else {
        setError(requestError instanceof Error ? requestError.message : '提交失败');
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <View style={common.screen}>
      <View style={styles.header}>
        <Pressable onPress={onClose} style={styles.back}>
          <Text style={styles.backText}>取消</Text>
        </Pressable>
        <View style={styles.headerCenter}>
          <Text style={common.title}>
            {initialNames ? '修正' : '记录'}{MEAL_LABELS[mealType]}草稿
          </Text>
          <Text style={common.meta}>{date}</Text>
        </View>
        <View style={styles.back} />
      </View>

      <ScrollView contentContainerStyle={common.page} keyboardShouldPersistTaps="handled">
        <View style={common.card}>
          <Text style={common.label}>AI 配餐（本餐目标为准，全天缺口仅提示）</Text>
          {!plan ? (
            <Button label="AI 生成这一餐" variant="soft" onPress={() => void generatePlan()} disabled={busy} />
          ) : (
            <>
              {plan.items.map((item, index) => (
                <View key={`${item.name}-${index}`} style={styles.planRow}>
                  <Text style={styles.itemName}>{item.name}</Text>
                  <Text style={common.meta}>{Math.round(item.amountG)} g</Text>
                </View>
              ))}
              {plan.explanation ? <Text style={common.meta}>{plan.explanation}</Text> : null}
              {plan.source?.startsWith('RULE_FALLBACK') ? (
                <Text style={common.meta}>来源：规则引擎兜底（source=RULE_FALLBACK:{plan.source.split(':')[1] ?? '1'}）</Text>
              ) : null}
              <View style={styles.planActions}>
                <Button label="换一批" variant="ghost" onPress={() => void revisePlan()} disabled={busy} />
                <Button label="记入草稿" onPress={() => void applyPlan()} disabled={busy} />
              </View>
            </>
          )}
        </View>

        <View style={common.card}>
          <Text style={common.label}>用一句话描述这一餐（AI 只出候选，确认后才计入）</Text>
          <TextInput
            style={common.input}
            value={aiText}
            onChangeText={setAiText}
            placeholder="例如：宫保鸡丁一份、米饭半碗"
            placeholderTextColor={colors.muted}
          />
          <Button label="AI 识别出候选" variant="soft" onPress={() => void analyze()} disabled={busy} />
        </View>

        <View style={common.card}>
          <View style={styles.draftHeader}>
            <Text style={common.label}>餐次草稿（点 ± 调整份量，逐项可改）</Text>
            <Pressable onPress={() => void copyPrevious()} disabled={busy}>
              <Text style={styles.linkText}>复制上一餐</Text>
            </Pressable>
          </View>
          {items.length === 0 ? (
            <Text style={common.meta}>还没有添加食物</Text>
          ) : (
            items.map((item) => (
              <ItemRow
                key={item.id}
                name={item.rawName}
                amountG={item.amountG}
                pending={item.confirmationStatus !== 'confirmed'}
                disabled={busy}
                onChangeAmount={(amountG) => void changeAmount(item, amountG)}
                onRemove={() => void removeItem(item)}
                onConfirm={() => void confirmItem(item)}
              />
            ))
          )}
        </View>

        <View style={common.card}>
          <Text style={common.label}>从食物库添加</Text>
          <TextInput
            style={common.input}
            value={query}
            onChangeText={setQuery}
            placeholder="搜索食物，如 米饭 / 鸡胸肉"
            placeholderTextColor={colors.muted}
          />
          {foods.map((food) => (
            <Pressable key={food.id} onPress={() => void addFood(food)} style={styles.foodRow}>
              <Text style={styles.itemName}>{food.name}</Text>
              <Text style={common.meta}>{Math.round(food.energyKcal)} kcal / 100g</Text>
            </Pressable>
          ))}
          {foods.length === 0 ? <Text style={common.meta}>没有匹配的食物</Text> : null}
        </View>

        {hint ? <Text style={common.meta}>{hint}</Text> : null}
        {error ? <Text style={common.error}>{error}</Text> : null}
        <ViolationBanner messages={violations} />
        {busy ? <ActivityIndicator color={colors.primary} /> : null}

        <Button
          label={initialNames ? '保存修正（产生新版本）' : '提交记录'}
          onPress={() => void submit()}
          disabled={busy || !group}
        />
      </ScrollView>

      {candidate ? (
        <AiCandidateSheet
          key={candidate.dishName + candidate.items.length}
          state={candidate}
          busy={busy}
          onConfirm={(confirmed) => void acceptCandidates(confirmed)}
          onClose={() => setCandidate(null)}
        />
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: spacing.lg,
    paddingTop: 56,
    paddingBottom: spacing.md,
    backgroundColor: colors.surface,
    borderBottomWidth: 1,
    borderBottomColor: colors.border
  },
  headerCenter: { alignItems: 'center', gap: 2 },
  back: { width: 56 },
  backText: { color: colors.primary, fontSize: 15 },
  planRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingVertical: 4,
    borderBottomWidth: 1,
    borderBottomColor: '#f0f2f5'
  },
  planActions: { flexDirection: 'row', justifyContent: 'flex-end', gap: spacing.sm },
  draftHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between'
  },
  linkText: { color: colors.primary, fontSize: 13, fontWeight: '600' },
  itemName: { fontSize: 15, color: colors.text },
  foodRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 8,
    borderBottomWidth: 1,
    borderBottomColor: '#f0f2f5'
  }
});
