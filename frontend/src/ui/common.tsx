import { Pressable, StyleSheet, Text, View } from 'react-native';
import { colors, spacing } from '../theme';

export function Button(props: {
  label: string;
  onPress: () => void;
  variant?: 'primary' | 'soft' | 'ghost';
  disabled?: boolean;
}) {
  const { label, onPress, variant = 'primary', disabled } = props;
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled}
      style={({ pressed }) => [
        styles.button,
        variant === 'primary' && styles.primary,
        variant === 'soft' && styles.soft,
        variant === 'ghost' && styles.ghost,
        pressed && !disabled && styles.pressed,
        disabled && styles.disabled
      ]}
    >
      <Text
        style={[
          styles.label,
          variant === 'primary' ? styles.labelOnPrimary : styles.labelDefault
        ]}
      >
        {label}
      </Text>
    </Pressable>
  );
}

export function ProgressBar(props: { ratio: number; over?: boolean; light?: boolean }) {
  const width = Math.min(Math.max(props.ratio, 0), 1);
  return (
    <View style={[styles.track, props.light && styles.trackLight]}>
      <View
        style={[
          styles.fill,
          { width: `${width * 100}%` },
          props.light && styles.fillLight,
          props.over && { backgroundColor: colors.warning }
        ]}
      />
    </View>
  );
}

export function Chip(props: { label: string; active?: boolean; onPress?: () => void }) {
  return (
    <Pressable
      onPress={props.onPress}
      style={[styles.chip, props.active && styles.chipActive]}
    >
      <Text style={[styles.chipText, props.active && styles.chipTextActive]}>{props.label}</Text>
    </Pressable>
  );
}

/** 硬规则阻断提示（红框），支持多条规则信息。 */
export function ViolationBanner(props: { messages: string[] }) {
  if (props.messages.length === 0) {
    return null;
  }
  return (
    <View style={styles.banner}>
      {props.messages.map((message, index) => (
        <Text key={`${index}-${message.slice(0, 12)}`} style={styles.bannerText}>
          ⚠ {message}
        </Text>
      ))}
    </View>
  );
}

/** 草稿项行：名称 + 待确认角标 + 步进器（±10g）+ 删除/确认操作。 */
export function ItemRow(props: {
  name: string;
  amountG: number;
  pending?: boolean;
  disabled?: boolean;
  onChangeAmount: (amountG: number) => void;
  onRemove: () => void;
  onConfirm?: () => void;
}) {
  const { name, amountG, pending, disabled } = props;
  return (
    <View style={styles.itemRow}>
      <View style={styles.itemNameWrap}>
        <Text style={styles.itemName}>{name}</Text>
        {pending ? <Text style={styles.pendingBadge}>待确认</Text> : null}
      </View>
      <Pressable
        disabled={disabled}
        style={[styles.step, disabled && styles.stepDisabled]}
        onPress={() => props.onChangeAmount(Math.max(5, amountG - 10))}
      >
        <Text style={styles.stepText}>−</Text>
      </Pressable>
      <Text style={styles.amount}>{Math.round(amountG)}g</Text>
      <Pressable
        disabled={disabled}
        style={[styles.step, disabled && styles.stepDisabled]}
        onPress={() => props.onChangeAmount(amountG + 10)}
      >
        <Text style={styles.stepText}>＋</Text>
      </Pressable>
      {pending && props.onConfirm ? (
        <Pressable onPress={props.onConfirm} style={styles.confirmButton}>
          <Text style={styles.confirmText}>确认</Text>
        </Pressable>
      ) : (
        <Pressable onPress={props.onRemove} disabled={disabled} style={styles.removeButton}>
          <Text style={styles.removeText}>删除</Text>
        </Pressable>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  button: {
    paddingVertical: 11,
    paddingHorizontal: spacing.lg,
    borderRadius: 10,
    alignItems: 'center',
    justifyContent: 'center'
  },
  primary: { backgroundColor: colors.primary },
  soft: { backgroundColor: colors.primarySoft },
  ghost: { backgroundColor: 'transparent', borderWidth: 1, borderColor: colors.border },
  pressed: { opacity: 0.75 },
  disabled: { opacity: 0.45 },
  label: { fontSize: 15, fontWeight: '600' },
  labelOnPrimary: { color: '#ffffff' },
  labelDefault: { color: colors.primary },
  track: {
    height: 8,
    borderRadius: 4,
    backgroundColor: '#e4e7eb',
    overflow: 'hidden'
  },
  fill: { height: '100%', borderRadius: 4, backgroundColor: colors.primary },
  trackLight: { backgroundColor: 'rgba(255,255,255,0.28)' },
  fillLight: { backgroundColor: '#ffffff' },
  chip: {
    paddingHorizontal: spacing.md,
    paddingVertical: 6,
    borderRadius: 999,
    backgroundColor: '#eef1f5'
  },
  chipActive: { backgroundColor: colors.primarySoft },
  chipText: { fontSize: 13, color: colors.muted },
  chipTextActive: { color: colors.primary, fontWeight: '600' },
  banner: {
    borderWidth: 1.5,
    borderColor: colors.danger,
    backgroundColor: '#fdeeec',
    borderRadius: 12,
    padding: spacing.md,
    gap: 4
  },
  bannerText: { color: colors.danger, fontSize: 13, lineHeight: 18 },
  itemRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    paddingVertical: 6,
    borderBottomWidth: 1,
    borderBottomColor: '#f0f2f5'
  },
  itemNameWrap: { flex: 1, flexDirection: 'row', alignItems: 'center', gap: 6 },
  itemName: { fontSize: 15, color: colors.text },
  pendingBadge: {
    fontSize: 10,
    color: colors.warning,
    borderWidth: 1,
    borderColor: colors.warning,
    borderRadius: 6,
    paddingHorizontal: 5,
    paddingVertical: 1,
    overflow: 'hidden'
  },
  step: {
    width: 28,
    height: 28,
    borderRadius: 14,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.primarySoft
  },
  stepDisabled: { opacity: 0.4 },
  stepText: { color: colors.primary, fontSize: 16, lineHeight: 18 },
  amount: { minWidth: 48, textAlign: 'center', fontSize: 14, color: colors.text },
  confirmButton: {
    paddingHorizontal: spacing.sm,
    paddingVertical: 5,
    borderRadius: 8,
    backgroundColor: colors.primary
  },
  confirmText: { color: '#ffffff', fontSize: 12, fontWeight: '600' },
  removeButton: { paddingHorizontal: spacing.sm },
  removeText: { color: colors.danger, fontSize: 13 }
});
