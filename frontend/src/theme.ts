import { StyleSheet } from 'react-native';

export const colors = {
  background: '#f2f4f7',
  surface: '#ffffff',
  border: '#e4e7eb',
  text: '#1f2933',
  muted: '#7b8794',
  primary: '#2f855a',
  primarySoft: '#e6f4ec',
  warning: '#b7791f',
  danger: '#c0392b'
};

export const spacing = {
  xs: 4,
  sm: 8,
  md: 12,
  lg: 16,
  xl: 24
};

export const common = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.background },
  page: { padding: spacing.lg, gap: spacing.md },
  card: {
    backgroundColor: colors.surface,
    borderRadius: 14,
    padding: spacing.lg,
    gap: spacing.sm
  },
  label: { fontSize: 13, color: colors.muted },
  value: { fontSize: 26, fontWeight: '700', color: colors.text },
  meta: { fontSize: 13, color: colors.muted },
  title: { fontSize: 18, fontWeight: '700', color: colors.text },
  input: {
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: 10,
    paddingHorizontal: spacing.md,
    paddingVertical: 10,
    fontSize: 15,
    color: colors.text,
    backgroundColor: colors.surface
  },
  row: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  error: { color: colors.danger, fontSize: 13 }
});
