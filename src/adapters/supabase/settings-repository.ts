import type { SupabaseClient } from '@supabase/supabase-js';
import type { Nutrients, UserSettings } from '../../shared/types.ts';
import { DEFAULT_DAILY_TARGETS } from '../../nutrition/targets.ts';

export interface SettingsStore {
  getSettings(userId: string): Promise<UserSettings | null>;
  saveSettings(userId: string, settings: UserSettings): Promise<void>;
}

export class SupabaseSettingsRepository implements SettingsStore {
  constructor(private readonly client: SupabaseClient) {}

  async getSettings(userId: string): Promise<UserSettings> {
    const { data, error } = await this.client
      .from('user_settings')
      .select('daily_targets, preferences, allergens, avoid')
      .eq('user_id', userId)
      .maybeSingle();
    if (error) throw error;

    return {
      dailyTargets: ({ ...DEFAULT_DAILY_TARGETS, ...(data?.daily_targets ?? {}) } as Nutrients),
      preferences: data?.preferences ?? [],
      allergens: data?.allergens ?? [],
      avoid: data?.avoid ?? []
    };
  }

  async saveSettings(userId: string, settings: UserSettings): Promise<void> {
    const { error } = await this.client.from('user_settings').upsert({
      user_id: userId,
      daily_targets: settings.dailyTargets,
      preferences: settings.preferences,
      allergens: settings.allergens,
      avoid: settings.avoid,
      updated_at: new Date().toISOString()
    });
    if (error) throw error;
  }
}
