export interface AppConfig {
  zhipu: {
    apiKey: string;
    baseUrl: string;
    chatModel: string;
    embeddingModel: string;
    dimensions: number;
    timeoutMs: number;
    maxRetries: number;
  };
  supabase: {
    url: string;
    anonKey: string;
    serviceRoleKey: string;
  };
}

function readNumber(value: string | undefined, fallback: number): number {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): AppConfig {
  return {
    zhipu: {
      apiKey: env.ZHIPU_API_KEY ?? '',
      baseUrl: env.ZHIPU_BASE_URL ?? 'https://open.bigmodel.cn/api/paas/v4',
      chatModel: env.ZHIPU_CHAT_MODEL ?? 'glm-5.3-flash',
      embeddingModel: env.ZHIPU_EMBEDDING_MODEL ?? 'embedding-3',
      dimensions: readNumber(env.EMBEDDING_DIMENSIONS, 1024),
      timeoutMs: readNumber(env.AI_TIMEOUT_MS, 20_000),
      maxRetries: readNumber(env.AI_MAX_RETRIES, 1)
    },
    supabase: {
      url: env.SUPABASE_URL ?? '',
      anonKey: env.SUPABASE_ANON_KEY ?? '',
      serviceRoleKey: env.SUPABASE_SERVICE_ROLE_KEY ?? ''
    }
  };
}
