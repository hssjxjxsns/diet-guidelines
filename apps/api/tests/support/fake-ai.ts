import type { z } from 'zod';
import type { AiCallLog, AiGateway, ChatJsonRequest } from '../../src/ai/gateway.ts';

export class FakeAiGateway implements AiGateway {
  calls: ChatJsonRequest[] = [];

  constructor(private readonly responses: unknown[]) {}

  async chatJson<T>(request: ChatJsonRequest, schema: z.ZodType<T>): Promise<T> {
    this.calls.push(request);
    if (this.responses.length === 0) throw new Error('no fake AI response left');
    return schema.parse(this.responses.shift());
  }

  async embed(texts: string[]): Promise<number[][]> {
    return texts.map(() => new Array(1024).fill(0));
  }
}

export function createLoggingAi(logs: AiCallLog[]): AiGateway {
  return {
    async chatJson<T>(_request: ChatJsonRequest, _schema: z.ZodType<T>): Promise<T> {
      throw new Error('not implemented');
    },
    async embed(texts: string[]): Promise<number[][]> {
      return texts.map(() => new Array(1024).fill(0));
    }
  };
}
