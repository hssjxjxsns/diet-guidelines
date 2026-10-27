import { useCallback, useEffect, useState } from 'react';
import {
  ActivityIndicator,
  Pressable,
  RefreshControl,
  ScrollView,
  StyleSheet,
  Text,
  View
} from 'react-native';
import { api, today } from '../api/client';
import type { DaySummary, MealType } from '../api/types';
import { supabase } from '../lib/supabase';

const MEAL_ORDER: MealType[] = ['breakfast', 'lunch', 'dinner', 'snack'];

const MEAL_LABELS: Record<MealType, string> = {
  breakfast: '早餐',
  lunch: '午餐',
  dinner: '晚餐',
  snack: '加餐'
};

export function TodayScreen() {
  const [date] = useState(today);
  const [summary, setSummary] = useState<DaySummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setSummary(await api.getDay(date));
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [date]);

  useEffect(() => {
    void load();
  }, [load]);

  const groups = [...(summary?.groups ?? [])].sort(
    (left, right) =>
      MEAL_ORDER.indexOf(left.group.mealType) - MEAL_ORDER.indexOf(right.group.mealType) ||
      left.group.instanceSeq - right.group.instanceSeq
  );

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <View>
          <Text style={styles.title}>今日</Text>
          <Text style={styles.subtitle}>{date}</Text>
        </View>
        <Pressable onPress={() => void supabase.auth.signOut()}>
          <Text style={styles.link}>退出</Text>
        </Pressable>
      </View>

      <ScrollView
        contentContainerStyle={styles.content}
        refreshControl={<RefreshControl refreshing={loading} onRefresh={() => void load()} />}
      >
        <View style={styles.card}>
          <Text style={styles.cardLabel}>已摄入能量</Text>
          <Text style={styles.cardValue}>
            {Math.round(summary?.dailyTotals.energyKcal ?? 0)} kcal
          </Text>
          <Text style={styles.cardMeta}>
            蛋白质 {format(summary?.dailyTotals.proteinG)} g · 脂肪{' '}
            {format(summary?.dailyTotals.fatG)} g · 碳水 {format(summary?.dailyTotals.carbG)} g
          </Text>
        </View>

        {error ? <Text style={styles.error}>{error}</Text> : null}

        {!loading && groups.length === 0 ? (
          <Text style={styles.empty}>今天还没有记录。规划并记录一餐后，这里会显示有效版本的汇总。</Text>
        ) : null}

        {groups.map(({ group, effective }) => (
          <View key={group.id} style={styles.card}>
            <Text style={styles.groupTitle}>
              {MEAL_LABELS[group.mealType]}
              {group.mealType === 'snack' ? ` #${group.instanceSeq}` : ''}
            </Text>
            {effective.length === 0 ? (
              <Text style={styles.cardMeta}>暂无有效记录</Text>
            ) : (
              effective.map((entry) => (
                <View key={entry.id} style={styles.entry}>
                  <Text style={styles.entryMeta}>
                    revision {entry.revision} · {entry.mode} · {entry.valueType}
                  </Text>
                  {entry.items.map((item, index) => (
                    <Text key={`${entry.id}-${index}`} style={styles.item}>
                      {item.rawName} {Math.round(item.amountG)} g
                    </Text>
                  ))}
                  <Text style={styles.entryMeta}>
                    {Math.round(entry.totals.energyKcal ?? 0)} kcal
                  </Text>
                </View>
              ))
            )}
          </View>
        ))}
      </ScrollView>
    </View>
  );
}

function format(value: number | undefined): string {
  return value === undefined ? '-' : value.toFixed(1);
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f7fa' },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 20,
    paddingTop: 64,
    paddingBottom: 12
  },
  title: { fontSize: 22, fontWeight: '600', color: '#1f2933' },
  subtitle: { fontSize: 13, color: '#7b8794' },
  link: { color: '#2f855a', fontSize: 14 },
  content: { padding: 16, gap: 12 },
  card: { backgroundColor: '#ffffff', borderRadius: 12, padding: 16 },
  cardLabel: { fontSize: 13, color: '#7b8794' },
  cardValue: { fontSize: 28, fontWeight: '600', color: '#1f2933', marginTop: 4 },
  cardMeta: { fontSize: 13, color: '#52606d', marginTop: 6 },
  groupTitle: { fontSize: 16, fontWeight: '600', color: '#1f2933', marginBottom: 8 },
  entry: { borderTopWidth: 1, borderTopColor: '#e4e7eb', paddingTop: 8, marginTop: 8 },
  entryMeta: { fontSize: 12, color: '#7b8794' },
  item: { fontSize: 14, color: '#1f2933', marginVertical: 2 },
  empty: { fontSize: 14, color: '#7b8794', paddingHorizontal: 4 },
  error: { color: '#c0392b', paddingHorizontal: 4 }
});
