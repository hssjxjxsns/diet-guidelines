import type { SupabaseClient } from 'npm:@supabase/supabase-js@2';

export const CORS_HEADERS: Record<string, string> = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  'Access-Control-Allow-Methods': 'GET, POST, PUT, PATCH, DELETE, OPTIONS'
};

export function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { ...CORS_HEADERS, 'Content-Type': 'application/json' }
  });
}

export function preflight(): Response {
  return new Response('ok', { headers: CORS_HEADERS });
}

export async function requireUser(
  request: Request,
  client: SupabaseClient
): Promise<{ id: string } | Response> {
  const header = request.headers.get('Authorization') ?? '';
  const token = header.replace(/^Bearer\s+/i, '');
  if (!token) return json({ code: 'UNAUTHORIZED', message: 'missing token' }, 401);

  const { data, error } = await client.auth.getUser(token);
  if (error || !data.user) return json({ code: 'UNAUTHORIZED', message: 'invalid token' }, 401);

  return { id: data.user.id };
}

/** 返回函数名之后的路径片段，兼容 /functions/v1/<name>/... 与 /<name>/... */
export function routeSegments(request: Request, functionName: string): string[] {
  const parts = new URL(request.url).pathname.split('/').filter(Boolean);
  const index = parts.lastIndexOf(functionName);
  return index === -1 ? [] : parts.slice(index + 1);
}

export function errorResponse(error: unknown): Response {
  console.error(error);
  if (error instanceof Error) {
    const status = (error as { status?: number }).status ?? 500;
    const code = (error as { code?: string }).code ?? 'INTERNAL_ERROR';
    return json({ code, message: error.message }, status);
  }
  return json({ code: 'INTERNAL_ERROR', message: 'unexpected error' }, 500);
}
