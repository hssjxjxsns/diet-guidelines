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
  chipTextActive: { color: colors.primary, fontWeight: '600' }
});
