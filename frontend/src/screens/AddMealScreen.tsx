import { useCallback, useEffect, useState } from 'react';
import {
  ActivityIndicator,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View
} from 'react-native';
import { api, ensureGroupId, newIdempotencyKey } from '../api/client';
import type { DishAnalysis, Food, MealEntryItem, MealType, PlanResult } from '../api/types';
import { Button } from '../ui/common';
import { colors, common, spacing } from '../theme';

const MEAL_LABELS: Record<MealType, string> = {
  breakfast: '早餐',
  lunch: '午餐',
  dinner: '晚餐',
  snack: '加餐'
};

interface DraftItem {
  key: string;
  foodId?: string;
  rawName: string;
  amountG: number;
  analysis?: DishAnalysis;
}

interface Props {
  date: string;
  mealType: MealType;
  edit?: { groupId: string; baseRevision: number };
  initialItems?: MealEntryItem[];
  onClose: () => void;
  onSubmitted: () => void;
}

export function AddMealScreen(props: Props) {
  const { date, mealType, edit, initialItems, onClose, onSubmitted } = props;

  const [items, setItems] = useState<DraftItem[]>(() =>
    (initialItems ?? []).map((item, index) => ({
      key: `init-${index}`,
      foodId: item.foodId,
      rawName: item.rawName,
      amountG: item.amountG
    }))
  );
  const [query, setQuery] = useState('');
  const [foods, setFoods] = useState<Food[]>([]);
  const [aiText, setAiText] = useState('');
  const [plan, setPlan] = useState<PlanResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [aiHint, setAiHint] = useState<string | null>(null);

  const loadFoods = useCallback(async (keyword: string) => {
    try {
      setFoods(await api.listFoods(keyword));
    } catch {
      setFoods([]);
    }
  }, []);

  useEffect(() => {
    void loadFoods('');
  }, [loadFoods]);

  useEffect(() => {
    const timer = setTimeout(() => void loadFoods(query), 300);
    return () => clearTimeout(timer);
  }, [query, loadFoods]);

  function addFood(food: Food) {
    setItems((previous) => [
      ...previous,
      { key: `${food.id}-${Date.now()}`, foodId: food.id, rawName: food.name, amountG: 100 }
    ]);
  }

  function updateItem(key: string, amountG: number) {
    setItems((previous) =>
      previous.map((item) => (item.key === key ? { ...item, amountG } : item))
    );
  }

  function removeItem(key: string) {
    setItems((previous) => previous.filter((item) => item.key !== key));
  }

  async function analyze() {
    if (!aiText.trim()) {
      return;
    }
    setBusy(true);
    setError(null);
    setAiHint(null);
    try {
      const analysis = await api.parseDish(aiText.trim());
      const ingredients = analysis.recipeProposal?.ingredients ?? [];
      if (ingredients.length > 0) {
        const next: DraftItem[] = ingredients.map((ingredient, index) => ({
          key: `ai-${Date.now()}-${index}`,
          foodId: ingredient.foodId,
          rawName: ingredient.name,
          amountG: ingredient.amountG ?? 100
        }));
        if (analysis.recipeProposal?.oilG) {
          next.push({
            key: `ai-oil-${Date.now()}`,
            rawName: '植物油',
            amountG: analysis.recipeProposal.oilG
          });
        }
        setItems((previous) => [...previous, ...next]);
        setAiHint(`已拆解「${analysis.dishName ?? aiText}」，共 ${next.length} 项，请核对克数`);
      } else if (analysis.directEstimate) {
        setItems((previous) => [
          ...previous,
          {
            key: `ai-${Date.now()}`,
            rawName: analysis.dishName ?? aiText.trim(),
            amountG: analysis.recipeProposal?.yieldG ?? 100,
            analysis
          }
        ]);
        setAiHint('AI 仅给出区间估算，已按估值记录');
      } else {
        setError('AI 未能识别这道菜，请手动添加食物');
      }
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : '识别失败');
    } finally {
      setBusy(false);
    }
  }

  async function generatePlan() {
    setBusy(true);
    setError(null);
    setAiHint(null);
    try {
      const result = await api.createPlan(date, mealType);
      applyPlan(result);
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
      const result = await api.revisePlan(plan.sessionId, plan.items);
      applyPlan(result);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : '换菜失败');
    } finally {
      setBusy(false);
    }
  }

  function applyPlan(result: PlanResult) {
    setPlan(result);
    if (result.items.length > 0) {
      setItems(
        result.items.map((item, index) => ({
          key: `plan-${Date.now()}-${index}`,
          foodId: item.foodId,
          rawName: item.name,
          amountG: item.amountG
        }))
      );
    }
    if (result.fallback) {
      setAiHint('AI 暂不可用，这份方案由本地规则生成');
    }
  }

  async function submit() {
    if (items.length === 0) {
      setError('请先添加至少一种食物');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const groupId = edit?.groupId ?? (await ensureGroupId(date, mealType));
      await api.submitEntry(groupId, {
        mode: edit ? 'replace' : 'append',
        base_revision: edit?.baseRevision,
        idempotency_key: newIdempotencyKey(),
        items: items.map((item) => ({
          foodId: item.foodId,
          rawName: item.rawName,
          amountG: item.amountG,
          valueType: item.analysis ? 'ai_estimated' : 'measured',
          analysis: item.analysis
        }))
      });
      onSubmitted();
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : '提交失败');
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
          <Text style={common.title}>{edit ? '修正' : '记录'}{MEAL_LABELS[mealType]}</Text>
          <Text style={common.meta}>{date}</Text>
        </View>
        <View style={styles.back} />
      </View>

      <ScrollView contentContainerStyle={common.page} keyboardShouldPersistTaps="handled">
        <View style={common.card}>
          <Text style={styles.planTitle}>AI 配餐</Text>
          <Text style={common.meta}>按每日目标和今天已吃的部分，自动生成这一餐吃什么、多少克。</Text>
          <Button label="AI 生成这一餐" onPress={() => void generatePlan()} disabled={busy} />
          {plan ? (
            <>
              {plan.explanation ? <Text style={styles.planText}>{plan.explanation}</Text> : null}
              {plan.validation?.failedRules?.length ? (
                <Text style={common.error}>
                  未通过校验：{plan.validation.failedRules.map((rule) => rule.message).join('；')}
                </Text>
              ) : null}
              {plan.gap?.remaining?.energyKcal !== undefined ? (
                <Text style={common.meta}>
                  今日还可摄入约 {Math.round(plan.gap.remaining.energyKcal)} kcal
                </Text>
              ) : null}
              <Button label="换一批" variant="ghost" onPress={() => void revisePlan()} disabled={busy} />
            </>
          ) : null}
        </View>

        <View style={common.card}>
          <Text style={common.label}>用一句话描述这一餐</Text>
          <TextInput
            style={common.input}
            value={aiText}
            onChangeText={setAiText}
            placeholder="例如：番茄炒蛋一份、米饭一碗"
            placeholderTextColor={colors.muted}
          />
          <Button label="AI 识别并拆解" variant="soft" onPress={() => void analyze()} disabled={busy} />
          {aiHint ? <Text style={common.meta}>{aiHint}</Text> : null}
        </View>

        <View style={common.card}>
          <Text style={common.label}>本次记录（点克数可修改）</Text>
          {items.length === 0 ? (
            <Text style={common.meta}>还没有添加食物</Text>
          ) : (
            items.map((item) => (
              <View key={item.key} style={styles.itemRow}>
                <View style={styles.itemNameWrap}>
                  <Text style={styles.itemName}>{item.rawName}</Text>
                  {item.analysis ? <Text style={common.meta}>AI 估值</Text> : null}
                </View>
                <TextInput
                  style={styles.amountInput}
                  keyboardType="numeric"
                  value={`${Math.round(item.amountG)}`}
                  onChangeText={(text) => updateItem(item.key, Number(text.replace(/[^0-9.]/g, '')) || 0)}
                />
                <Text style={common.meta}>g</Text>
                <Pressable onPress={() => removeItem(item.key)} style={styles.remove}>
                  <Text style={styles.removeText}>删除</Text>
                </Pressable>
              </View>
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
            <Pressable key={food.id} onPress={() => addFood(food)} style={styles.foodRow}>
              <Text style={styles.itemName}>{food.name}</Text>
              <Text style={common.meta}>
                {Math.round(food.nutrientsPer100g.energyKcal ?? 0)} kcal / 100g
              </Text>
            </Pressable>
          ))}
          {foods.length === 0 ? <Text style={common.meta}>没有匹配的食物</Text> : null}
        </View>

        {error ? <Text style={common.error}>{error}</Text> : null}

        {busy ? <ActivityIndicator color={colors.primary} /> : null}

        <Button
          label={edit ? '保存修正' : '提交记录'}
          onPress={() => void submit()}
          disabled={busy}
        />
      </ScrollView>
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
  itemRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    paddingVertical: 6,
    borderBottomWidth: 1,
    borderBottomColor: '#f0f2f5'
  },
  itemNameWrap: { flex: 1 },
  itemName: { fontSize: 15, color: colors.text },
  amountInput: {
    width: 68,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: 8,
    paddingHorizontal: spacing.sm,
    paddingVertical: 6,
    textAlign: 'right',
    fontSize: 15,
    color: colors.text
  },
  remove: { paddingHorizontal: spacing.sm },
  removeText: { color: colors.danger, fontSize: 13 },
  foodRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 8,
    borderBottomWidth: 1,
    borderBottomColor: '#f0f2f5'
  },
  planTitle: { fontSize: 16, fontWeight: '700', color: colors.text },
  planText: { fontSize: 14, lineHeight: 20, color: colors.text }
});
