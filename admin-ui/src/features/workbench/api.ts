import type {
  AgentManifest,
  ChatCompletionResponse,
  ChatMessage,
  DomainManifest,
  TraceHealth,
} from './types'
import { readAdminToken } from '../../auth/tokenStorage'

const DEFAULT_GATEWAY_BASE = '/gateway-api'

function gatewayBase(): string {
  const configured = import.meta.env.VITE_GATEWAY_API_BASE as string | undefined
  return (configured?.replace(/\/$/, '') || DEFAULT_GATEWAY_BASE)
}

export function gatewayToken(): string {
  return readAdminToken()
}

export function gatewayAuthHeaders(): Record<string, string> {
  const token = gatewayToken()
  return token ? { Authorization: `Bearer ${token}` } : {}
}

async function gatewayReq<T>(
  method: string,
  path: string,
  body?: unknown,
  extraHeaders: Record<string, string> = {},
): Promise<T> {
  const res = await fetch(`${gatewayBase()}${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...gatewayAuthHeaders(),
      ...extraHeaders,
    },
    ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
  })

  if (!res.ok) {
    const err = await res.json().catch(() => ({ error: res.statusText }))
    throw new Error(err.detail?.message || err.detail || err.error || res.statusText)
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
  sendChatTurnMvp: (conversationId: string, messages: ChatMessage[]) =>
    gatewayReq<ChatCompletionResponse>(
      'POST',
      '/v1/chat/completions',
      {
        model: 'conduit-assistant',
        stream: false,
        messages,
      },
      { 'X-Conversation-Id': conversationId },
    ),
}

export function traceStreamRequest(): { url: string; headers: Record<string, string> } {
  return {
    url: `${gatewayBase()}/trace/stream`,
    headers: {
      Accept: 'text/event-stream',
      ...gatewayAuthHeaders(),
    },
  }
}
