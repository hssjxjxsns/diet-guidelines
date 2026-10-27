import type { z } from 'zod';
import { AiInvalidOutputError, AiUnavailableError } from '../shared/errors.ts';
import type { AppConfig } from '../config.ts';

export interface ChatJsonRequest {
  scene: string;
  system: string;
  user: string;
  maxOutputTokens: number;
  temperature?: number;
  images?: string[];
}

export interface AiCallLog {
  scene: string;
  model: string;
  promptTokens: number;
  completionTokens: number;
  latencyMs: number;
  status: 'ok' | 'error';
}

export interface AiGateway {
  chatJson<T>(request: ChatJsonRequest, schema: z.ZodType<T>): Promise<T>;
  embed(texts: string[]): Promise<number[][]>;
}

interface ZhipuChatResponse {
  choices?: Array<{ message?: { content?: string } }>;
  usage?: { prompt_tokens?: number; completion_tokens?: number };
}

interface ZhipuEmbeddingResponse {
  data?: Array<{ embedding: number[] }>;
}

export class ZhipuGateway implements AiGateway {
  constructor(
    private readonly config: AppConfig['zhipu'],
    private readonly onCall?: (log: AiCallLog) => void
  ) {}

  async chatJson<T>(request: ChatJsonRequest, schema: z.ZodType<T>): Promise<T> {
    if (!this.config.apiKey) throw new AiUnavailableError('ZHIPU_API_KEY is not configured');

    const startedAt = Date.now();
    let responseText = '';
    let usage: ZhipuChatResponse['usage'];

    try {
      const payload = {
        model: this.config.chatModel,
        messages: [
          { role: 'system', content: request.system },
          { role: 'user', content: this.buildUserContent(request) }
        ],
        temperature: request.temperature ?? 0.3,
        max_tokens: request.maxOutputTokens,
        response_format: { type: 'json_object' }
      };

      const response = await this.request('/chat/completions', payload);
      const data = (await response.json()) as ZhipuChatResponse;
      responseText = data.choices?.[0]?.message?.content ?? '';
      usage = data.usage;
    } catch (error) {
      this.onCall?.({
        scene: request.scene,
        model: this.config.chatModel,
        promptTokens: 0,
        completionTokens: 0,
        latencyMs: Date.now() - startedAt,
        status: 'error'
      });
      if (error instanceof AiUnavailableError) throw error;
      throw new AiUnavailableError(error instanceof Error ? error.message : 'AI request failed');
    }

    this.onCall?.({
      scene: request.scene,
      model: this.config.chatModel,
      promptTokens: usage?.prompt_tokens ?? 0,
      completionTokens: usage?.completion_tokens ?? 0,
      latencyMs: Date.now() - startedAt,
      status: 'ok'
    });

    const parsedJson = parseJsonObject(responseText);
    const parsed = schema.safeParse(parsedJson);
    if (!parsed.success) {
      throw new AiInvalidOutputError('AI output does not match schema', parsed.error.flatten());
    }
    return parsed.data;
  }

  async embed(texts: string[]): Promise<number[][]> {
    if (!this.config.apiKey) throw new AiUnavailableError('ZHIPU_API_KEY is not configured');
    if (texts.length === 0) return [];
    if (texts.length > 64) throw new AiInvalidOutputError('Embedding batch must be <= 64 texts');

    const response = await this.request('/embeddings', {
      model: this.config.embeddingModel,
      input: texts,
      dimensions: this.config.dimensions
    });
    const data = (await response.json()) as ZhipuEmbeddingResponse;
    return (data.data ?? []).map((item) => item.embedding);
  }

  private buildUserContent(request: ChatJsonRequest): unknown {
    if (!request.images || request.images.length === 0) return request.user;
    return [
      { type: 'text', text: request.user },
      ...request.images.map((url) => ({ type: 'image_url', image_url: { url } }))
    ];
  }

  private async request(path: string, payload: unknown): Promise<Response> {
    let lastError: unknown;

    for (let attempt = 0; attempt <= this.config.maxRetries; attempt += 1) {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), this.config.timeoutMs);

      try {
        const response = await fetch(`${this.config.baseUrl}${path}`, {
          method: 'POST',
          headers: {
            Authorization: `Bearer ${this.config.apiKey}`,
            'Content-Type': 'application/json'
          },
          body: JSON.stringify(payload),
          signal: controller.signal
        });

        if (response.ok) return response;

        const retryable = response.status === 429 || response.status >= 500;
        const body = await response.text();
        lastError = new Error(`Zhipu API ${response.status}: ${body}`);
        if (!retryable) break;
      } catch (error) {
        lastError = error;
      } finally {
        clearTimeout(timer);
      }
    }

    throw lastError instanceof Error ? lastError : new Error('Zhipu API request failed');
  }
}

export function parseJsonObject(text: string): unknown {
  const trimmed = text.trim();
  const withoutFence = trimmed.replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/, '');
  const start = withoutFence.indexOf('{');
  const end = withoutFence.lastIndexOf('}');

  if (start === -1 || end === -1 || end <= start) {
    throw new AiInvalidOutputError('AI output is not a JSON object');
  }

  try {
    return JSON.parse(withoutFence.slice(start, end + 1));
  } catch {
    throw new AiInvalidOutputError('AI output contains invalid JSON');
  }
}
