import type {
  AgentManifest,
  ChatCompletionResponse,
  ChatMessage,
  DomainManifest,
  TraceHealth,
} from './types'
import { clearAdminToken, notifyAuthLogout, readAdminToken } from '../../auth/tokenStorage'

const DEFAULT_GATEWAY_BASE = '/gateway-api'

function gatewayBase(): string {
  const configured = import.meta.env.VITE_GATEWAY_API_BASE as string | undefined
  return (configured?.replace(/\/$/, '') || DEFAULT_GATEWAY_BASE)
}

function broadcastLogout(): void {
  clearAdminToken()
  notifyAuthLogout()
}

export function gatewayAuthHeaders(authToken?: string): Record<string, string> {
  const token = authToken || readAdminToken()
  return token ? { Authorization: `Bearer ${token}` } : {}
}

function errorMessage(payload: unknown, fallback: string): string {
  if (!payload || typeof payload !== 'object') return fallback
  const err = payload as Record<string, unknown>
  const detail = err.detail
  if (typeof detail === 'string') return detail
  if (detail && typeof detail === 'object') {
    const message = (detail as Record<string, unknown>).message
    if (typeof message === 'string') return message
  }
  if (typeof err.message === 'string') return err.message
  if (typeof err.error === 'string') return err.error
  return fallback
}

async function gatewayReq<T>(
  method: string,
  path: string,
  body?: unknown,
  extraHeaders: Record<string, string> = {},
  signal?: AbortSignal,
  authToken?: string,
): Promise<T> {
  const res = await fetch(`${gatewayBase()}${path}`, {
    method,
    signal,
    headers: {
      'Content-Type': 'application/json',
      ...gatewayAuthHeaders(authToken),
      ...extraHeaders,
    },
    ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
  })

  if (!res.ok) {
    if (res.status === 401 && !authToken) broadcastLogout()
    const err = await res.json().catch(() => ({ error: res.statusText }))
    throw new Error(errorMessage(err, res.statusText || `Request failed (${res.status})`))
  }

  if (res.status === 204) return undefined as T
  return res.json()
}

export const workbenchApi = {
  traceHealth: () =>
    gatewayReq<TraceHealth>('GET', '/trace/health'),

  listDomains: () =>
    gatewayReq<Record<string, DomainManifest>>('GET', '/admin/domains'),

  listAgents: () =>
    gatewayReq<AgentManifest[]>('GET', '/admin/agents'),

  // MVP path: non-streaming keeps this slice simple while the workbench shell and
  // trace rail land. The chat panel labels this as non-streaming so it is not
  // confused with the intended production streaming composer.
  sendChatTurnMvp: (conversationId: string, messages: ChatMessage[], authToken: string, signal?: AbortSignal) =>
    gatewayReq<ChatCompletionResponse>(
      'POST',
      '/v1/chat/completions',
      {
        model: 'conduit-assistant',
        stream: false,
        messages,
      },
      { 'X-Conversation-Id': conversationId },
      signal,
      authToken,
    ),
}

export function traceStreamRequest(conversationId: string, authToken: string): { url: string; headers: Record<string, string> } {
  const params = new URLSearchParams({ conversationId })
  return {
    url: `${gatewayBase()}/trace/stream?${params.toString()}`,
    headers: {
      Accept: 'text/event-stream',
      ...gatewayAuthHeaders(authToken),
    },
  }
}
