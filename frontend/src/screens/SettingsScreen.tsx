import { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { api } from '../api/client';
import type { Nutrients, UserSettings } from '../api/types';
import { Button, Chip } from '../ui/common';
import { colors, common, spacing } from '../theme';

const TARGET_FIELDS: Array<{ key: keyof Nutrients; label: string }> = [
  { key: 'energyKcal', label: '能量 (kcal)' },
  { key: 'proteinG', label: '蛋白质 (g)' },
  { key: 'fatG', label: '脂肪 (g)' },
  { key: 'carbG', label: '碳水 (g)' }
];

type Sex = 'male' | 'female';
type Goal = 'cut' | 'maintain' | 'bulk';

const ACTIVITY_LEVELS: Array<{ key: number; label: string }> = [
  { key: 1.2, label: '久坐' },
  { key: 1.375, label: '轻度' },
  { key: 1.55, label: '中度' },
  { key: 1.725, label: '高强度' }
];

export function SettingsScreen() {
  const [settings, setSettings] = useState<UserSettings | null>(null);
  const [targets, setTargets] = useState<Record<string, string>>({});
  const [preferences, setPreferences] = useState('');
  const [allergens, setAllergens] = useState('');
  const [avoid, setAvoid] = useState('');
  const [sex, setSex] = useState<Sex>('male');
  const [age, setAge] = useState('30');
  const [height, setHeight] = useState('175');
  const [weight, setWeight] = useState('70');
  const [activity, setActivity] = useState(1.375);
  const [goal, setGoal] = useState<Goal>('maintain');
  const [calcHint, setCalcHint] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      const value = await api.getSettings();
      setSettings(value);
      setTargets(
        Object.fromEntries(
          TARGET_FIELDS.map((field) => [
            field.key,
            value.dailyTargets?.[field.key] ? `${Math.round(value.dailyTargets[field.key]!)}` : ''
          ])
        )
      );
      setPreferences(value.preferences.join('、'));
      setAllergens(value.allergens.join('、'));
      setAvoid(value.avoid.join('、'));
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : '加载失败');
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  /** 用 Mifflin-St Jeor 估算基础代谢，再按活动量与目标换算每日能量与宏量营养素。 */
  function computeTargets() {
    const w = Number(weight);
    const h = Number(height);
    const a = Number(age);
    if (!w || !h || !a) {
      setCalcHint('请先填写年龄、身高、体重');
      return;
    }
    const bmr = 10 * w + 6.25 * h - 5 * a + (sex === 'male' ? 5 : -161);
    const tdee = bmr * activity;
    const factor = goal === 'cut' ? 0.85 : goal === 'bulk' ? 1.1 : 1;
    const energy = Math.round(tdee * factor);
    const proteinPerKg = goal === 'cut' ? 1.6 : goal === 'bulk' ? 1.8 : 1.2;
    const protein = Math.round(w * proteinPerKg);
    const fat = Math.round((energy * 0.25) / 9);
    const carb = Math.max(0, Math.round((energy - protein * 4 - fat * 9) / 4));

    setTargets({
      energyKcal: `${energy}`,
      proteinG: `${protein}`,
      fatG: `${fat}`,
      carbG: `${carb}`
    });
    setCalcHint(
      `基础代谢 ${Math.round(bmr)} kcal，日常消耗约 ${Math.round(tdee)} kcal，已按「${
        goal === 'cut' ? '减脂' : goal === 'bulk' ? '增肌' : '维持'
      }」给出目标，记得点下方保存`
    );
  }

  async function save() {
    setBusy(true);
    setError(null);
    setMessage(null);
    try {
      const dailyTargets: Record<string, number> = {};
      for (const field of TARGET_FIELDS) {
        const raw = targets[field.key];
        if (raw && Number(raw) > 0) {
          dailyTargets[field.key] = Number(raw);
        }
      }
      await api.saveSettings({
        dailyTargets,
        preferences: split(preferences),
        allergens: split(allergens),
        avoid: split(avoid)
      });
      setMessage('已保存');
      await load();
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : '保存失败');
    } finally {
      setBusy(false);
    }
  }

  return (
    <ScrollView
      style={common.screen}
      contentContainerStyle={common.page}
      keyboardShouldPersistTaps="handled"
    >
      <View style={common.card}>
        <Text style={common.title}>身体数据</Text>
        <Text style={common.meta}>填好后点计算，自动得出每日能量与三大营养素目标。</Text>
        <View style={styles.chipRow}>
          <Chip label="男" active={sex === 'male'} onPress={() => setSex('male')} />
          <Chip label="女" active={sex === 'female'} onPress={() => setSex('female')} />
        </View>
        <View style={styles.tripleRow}>
          <NumberField label="年龄" value={age} onChange={setAge} />
          <NumberField label="身高 cm" value={height} onChange={setHeight} />
          <NumberField label="体重 kg" value={weight} onChange={setWeight} />
        </View>
        <Text style={common.label}>活动量</Text>
        <View style={styles.chipRow}>
          {ACTIVITY_LEVELS.map((level) => (
            <Chip
              key={level.key}
              label={level.label}
              active={activity === level.key}
              onPress={() => setActivity(level.key)}
            />
          ))}
        </View>
        <Text style={common.label}>目标</Text>
        <View style={styles.chipRow}>
          <Chip label="减脂" active={goal === 'cut'} onPress={() => setGoal('cut')} />
          <Chip label="维持" active={goal === 'maintain'} onPress={() => setGoal('maintain')} />
          <Chip label="增肌" active={goal === 'bulk'} onPress={() => setGoal('bulk')} />
        </View>
        <Button label="计算推荐目标" variant="soft" onPress={computeTargets} />
        {calcHint ? <Text style={common.meta}>{calcHint}</Text> : null}
      </View>

      <View style={common.card}>
        <Text style={common.title}>每日目标</Text>
        {TARGET_FIELDS.map((field) => (
          <View key={String(field.key)} style={styles.fieldRow}>
            <Text style={styles.fieldLabel}>{field.label}</Text>
            <TextInput
              style={styles.numberInput}
              keyboardType="numeric"
              value={targets[field.key] ?? ''}
              onChangeText={(text) =>
                setTargets((previous) => ({ ...previous, [field.key]: text.replace(/[^0-9.]/g, '') }))
              }
            />
          </View>
        ))}
      </View>

      <View style={common.card}>
        <Text style={common.title}>饮食限制</Text>
        <Text style={common.label}>忌口（用「、」或逗号分隔）</Text>
        <TextInput
          style={common.input}
          value={avoid}
          onChangeText={setAvoid}
          placeholder="例如：香菜、动物内脏"
          placeholderTextColor={colors.muted}
        />
        <Text style={common.label}>过敏原</Text>
        <TextInput
          style={common.input}
          value={allergens}
          onChangeText={setAllergens}
          placeholder="例如：花生、牛奶"
          placeholderTextColor={colors.muted}
        />
        <Text style={common.label}>偏好</Text>
        <TextInput
          style={common.input}
          value={preferences}
          onChangeText={setPreferences}
          placeholder="例如：清淡、少油"
          placeholderTextColor={colors.muted}
        />
      </View>

      {settings ? (
        <Text style={common.meta}>
          提示：目标用于今日页的达标进度，限制用于校验与 AI 建议。
        </Text>
      ) : null}
      {error ? <Text style={common.error}>{error}</Text> : null}
      {message ? <Text style={styles.success}>{message}</Text> : null}
      {busy ? <ActivityIndicator color={colors.primary} /> : null}

      <Button label="保存设置" onPress={() => void save()} disabled={busy} />
    </ScrollView>
  );
}

function NumberField(props: { label: string; value: string; onChange: (value: string) => void }) {
  return (
    <View style={styles.numberField}>
      <Text style={styles.numberLabel}>{props.label}</Text>
      <TextInput
        style={[styles.numberInput, styles.numberInputFull]}
        keyboardType="numeric"
        value={props.value}
        onChangeText={(text) => props.onChange(text.replace(/[^0-9.]/g, ''))}
      />
    </View>
  );
}

function split(value: string): string[] {
  return value
    .split(/[、,，\s]+/)
    .map((item) => item.trim())
    .filter(Boolean);
}

const styles = StyleSheet.create({
  fieldRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: spacing.md
  },
  fieldLabel: { fontSize: 14, color: colors.text },
  numberInput: {
    width: 110,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: 8,
    paddingHorizontal: spacing.sm,
    paddingVertical: 6,
    textAlign: 'right',
    fontSize: 15,
    color: colors.text
  },
  success: { color: colors.primary, fontSize: 13 },
  chipRow: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
  tripleRow: { flexDirection: 'row', gap: spacing.md },
  numberField: { flex: 1, gap: 4 },
  numberLabel: { fontSize: 12, color: colors.muted },
  numberInputFull: { width: '100%', textAlign: 'left' }
});
