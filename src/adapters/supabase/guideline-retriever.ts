import type { SupabaseClient } from '@supabase/supabase-js';
import type { GuidelineRetriever } from '../../services/planning.ts';
import type { GuidelineChunk } from '../../shared/types.ts';
import type { AiGateway } from '../../ai/gateway.ts';

export class SupabaseGuidelineRetriever implements GuidelineRetriever {
  constructor(
    private readonly client: SupabaseClient,
    private readonly ai: AiGateway
  ) {}

  async retrieve(query: string, limit: number): Promise<GuidelineChunk[]> {
    const [embedding] = await this.ai.embed([query]);
    if (!embedding) return [];

    const { data, error } = await this.client.rpc('match_guideline_chunks', {
      query_embedding: embedding,
      match_count: limit
    });
    if (error) throw error;

    return (data ?? []).map((row: Record<string, unknown>) => ({
      id: String(row.id),
      content: String(row.content),
      metadata: (row.metadata ?? {}) as Record<string, unknown>,
      score: typeof row.similarity === 'number' ? row.similarity : undefined
    }));
  }
}
