import { useState } from 'react';
import { Modal, Pressable, StyleSheet, Text, View } from 'react-native';
import { StatusBar } from 'expo-status-bar';
import { today } from './api/client';
import type { MealEntryItem, MealType } from './api/types';
import { AddMealScreen } from './screens/AddMealScreen';
import { ReportsScreen } from './screens/ReportsScreen';
import { SettingsScreen } from './screens/SettingsScreen';
import { TodayScreen } from './screens/TodayScreen';
import { colors } from './theme';

type Tab = 'today' | 'reports' | 'settings';

interface EditorState {
  mealType: MealType;
  /** 修正已记录餐次：携带 instanceSeq 以重开该组的版本链。 */
  editInstanceSeq?: number;
  initialNames?: string[];
}

const TABS: Array<{ key: Tab; label: string }> = [
  { key: 'today', label: '今日' },
  { key: 'reports', label: '报表' },
  { key: 'settings', label: '设置' }
];

export default function App() {
  const [tab, setTab] = useState<Tab>('today');
  const [date, setDate] = useState(today);
  const [refreshToken, setRefreshToken] = useState(0);
  const [editor, setEditor] = useState<EditorState | null>(null);

  return (
    <View style={styles.container}>
      <StatusBar style="dark" />

      <View style={styles.body}>
        {tab === 'today' ? (
          <TodayScreen
            date={date}
            onChangeDate={setDate}
            onAdd={(mealType) => setEditor({ mealType })}
            onEdit={({ group, items }) =>
              setEditor({
                mealType: group.mealType,
                editInstanceSeq: group.instanceSeq,
                initialNames: items.map((item) => item.rawName)
              })
            }
            onRefreshRequest={() => setRefreshToken((value) => value + 1)}
            refreshToken={refreshToken}
          />
        ) : null}
        {tab === 'reports' ? <ReportsScreen /> : null}
        {tab === 'settings' ? <SettingsScreen /> : null}
      </View>

      <View style={styles.tabBar}>
        {TABS.map((item) => (
          <Pressable
            key={item.key}
            style={styles.tabItem}
            onPress={() => setTab(item.key)}
          >
            <Text style={[styles.tabLabel, tab === item.key && styles.tabLabelActive]}>
              {item.label}
            </Text>
          </Pressable>
        ))}
      </View>

      <Modal visible={editor !== null} animationType="slide" onRequestClose={() => setEditor(null)}>
        {editor ? (
          <AddMealScreen
            key={`${editor.mealType}-${editor.editInstanceSeq ?? 'new'}-${refreshToken}`}
            date={date}
            mealType={editor.mealType}
            editInstanceSeq={editor.editInstanceSeq}
            initialNames={editor.initialNames}
            onClose={() => setEditor(null)}
            onSubmitted={() => {
              setEditor(null);
              setRefreshToken((value) => value + 1);
            }}
          />
        ) : null}
      </Modal>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: colors.background },
  body: { flex: 1, paddingTop: 42 },
  tabBar: {
    flexDirection: 'row',
    backgroundColor: colors.surface,
    borderTopWidth: 1,
    borderTopColor: colors.border,
    paddingBottom: 18,
    paddingTop: 10
  },
  tabItem: { flex: 1, alignItems: 'center' },
  tabLabel: { fontSize: 14, color: colors.muted },
  tabLabelActive: { color: colors.primary, fontWeight: '700' }
});
