import { iterateSseData } from './sse'
import type {
  AgentManifest,
  ChatCompletionChunk,
  ChatCompletionResponse,
  ChatMessage,
  DomainManifest,
  TraceEvent,
  TraceHealth,
} from './types'

const DEFAULT_GATEWAY_BASE = '/gateway-api'
const DEFAULT_MODEL = 'conduit-assistant'
const SSE_DONE = '[DONE]'

export interface GatewayClientOptions {
  /** Base URL for gateway calls. Defaults to `/gateway-api` (proxy path). */
  baseUrl?: string
  /**
   * Bearer token provider. Called per request so a rotating/persona token is
   * always current. Return `undefined`/empty to send no Authorization header.
   */
  getToken?: () => string | null | undefined
  /** Invoked on a 401 when the request used the ambient (getToken) token. */
  onUnauthorized?: () => void
  /** Injectable fetch (tests / non-browser runtimes). Defaults to global fetch. */
  fetchImpl?: typeof fetch
}

export interface ChatRequest {
  messages: ChatMessage[]
  model?: string
  conversationId?: string
  /** Explicit per-call token; overrides `getToken` and suppresses onUnauthorized. */
  token?: string
  signal?: AbortSignal
}

export interface TraceStreamRequest {
  conversationId: string
  token?: string
  signal?: AbortSignal
}

function normalizeBase(base: string): string {
  return base.replace(/\/$/, '')
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

/**
 * Small, typed, framework-agnostic client for the Conduit gateway.
 *
 * Covers the OpenAI-compatible chat surface (streaming + non-streaming), the
 * glass-box trace stream, and the admin manifest endpoints. Carries no domain
 * knowledge — it only speaks the gateway's transport contract.
 */
export class GatewayClient {
  private readonly base: string
  private readonly getToken?: () => string | null | undefined
  private readonly onUnauthorized?: () => void
  private readonly fetchImpl: typeof fetch

  constructor(options: GatewayClientOptions = {}) {
    this.base = normalizeBase(options.baseUrl || DEFAULT_GATEWAY_BASE)
    this.getToken = options.getToken
    this.onUnauthorized = options.onUnauthorized
    this.fetchImpl = options.fetchImpl
      ? options.fetchImpl
      : (globalThis.fetch?.bind(globalThis) as typeof fetch)
    if (!this.fetchImpl) {
      throw new Error('GatewayClient: no fetch implementation available; pass options.fetchImpl')
    }
  }

  private authHeaders(explicitToken?: string): Record<string, string> {
    const token = explicitToken ?? this.getToken?.() ?? ''
    return token ? { Authorization: `Bearer ${token}` } : {}
  }

  private async raw(
    method: string,
    path: string,
    body: unknown | undefined,
    extraHeaders: Record<string, string>,
    signal: AbortSignal | undefined,
    explicitToken: string | undefined,
    accept = 'application/json',
  ): Promise<Response> {
    const res = await this.fetchImpl(`${this.base}${path}`, {
      method,
      signal,
      headers: {
        'Content-Type': 'application/json',
        Accept: accept,
        ...this.authHeaders(explicitToken),
        ...extraHeaders,
      },
      ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
    })

    if (!res.ok) {
      if (res.status === 401 && explicitToken === undefined) this.onUnauthorized?.()
      const payload = await res.clone().json().catch(() => ({ error: res.statusText }))
      throw new Error(errorMessage(payload, res.statusText || `Request failed (${res.status})`))
    }
    return res
  }

  private async request<T>(
    method: string,
    path: string,
    body?: unknown,
    extraHeaders: Record<string, string> = {},
    signal?: AbortSignal,
    explicitToken?: string,
  ): Promise<T> {
    const res = await this.raw(method, path, body, extraHeaders, signal, explicitToken)
    if (res.status === 204) return undefined as T
    return res.json() as Promise<T>
  }

  // ── Admin / diagnostics ────────────────────────────────────────────────
  traceHealth(signal?: AbortSignal): Promise<TraceHealth> {
    return this.request<TraceHealth>('GET', '/trace/health', undefined, {}, signal)
  }

  listDomains(signal?: AbortSignal): Promise<Record<string, DomainManifest>> {
    return this.request<Record<string, DomainManifest>>('GET', '/admin/domains', undefined, {}, signal)
  }

  listAgents(signal?: AbortSignal): Promise<AgentManifest[]> {
    return this.request<AgentManifest[]>('GET', '/admin/agents', undefined, {}, signal)
  }

  // ── Chat (OpenAI-compatible) ───────────────────────────────────────────
  /** Non-streaming completion. Returns the full response body. */
  chatCompletion(req: ChatRequest): Promise<ChatCompletionResponse> {
    const headers: Record<string, string> = req.conversationId ? { 'X-Conversation-Id': req.conversationId } : {}
    return this.request<ChatCompletionResponse>(
      'POST',
      '/v1/chat/completions',
      { model: req.model || DEFAULT_MODEL, stream: false, messages: req.messages },
      headers,
      req.signal,
      req.token,
    )
  }

  /**
   * Streaming completion. Yields each OpenAI-compatible chunk until the
   * `[DONE]` sentinel. Use `streamChatContent` for a plain text-delta stream.
   */
  async *streamChatCompletion(req: ChatRequest): AsyncGenerator<ChatCompletionChunk, void, unknown> {
    const headers: Record<string, string> = req.conversationId ? { 'X-Conversation-Id': req.conversationId } : {}
    const res = await this.raw(
      'POST',
      '/v1/chat/completions',
      { model: req.model || DEFAULT_MODEL, stream: true, messages: req.messages },
      headers,
      req.signal,
      req.token,
      'text/event-stream',
    )
    if (!res.body) throw new Error('Gateway returned no response body for streaming chat')
    for await (const data of iterateSseData(res.body)) {
      if (data === SSE_DONE) return
      yield JSON.parse(data) as ChatCompletionChunk
    }
  }

  /** Convenience: stream only the assistant text deltas. */
  async *streamChatContent(req: ChatRequest): AsyncGenerator<string, void, unknown> {
    for await (const chunk of this.streamChatCompletion(req)) {
      const delta = chunk.choices?.[0]?.delta?.content
      if (delta) yield delta
    }
  }

  // ── Trace stream (glass-box) ───────────────────────────────────────────
  /**
   * Subscribe to the gateway trace stream for a conversation. Yields parsed
   * `TraceEvent`s until the stream ends or the signal aborts. Callers decide
   * how to filter (e.g. by conversationId) and how to reconnect.
   */
  async *streamTraceEvents(req: TraceStreamRequest): AsyncGenerator<TraceEvent, void, unknown> {
    const params = new URLSearchParams({ conversationId: req.conversationId })
    const res = await this.fetchImpl(`${this.base}/trace/stream?${params.toString()}`, {
      signal: req.signal,
      headers: {
        Accept: 'text/event-stream',
        ...this.authHeaders(req.token),
      },
    })
    if (!res.ok || !res.body) {
      throw new Error(res.statusText || 'Trace stream unavailable')
    }
    for await (const data of iterateSseData(res.body)) {
      try {
        yield JSON.parse(data) as TraceEvent
      } catch {
        // Skip malformed frames; a bad frame should not tear down the stream.
      }
    }
  }

  /** Build the trace-stream URL + headers (for consumers that manage their own fetch/reconnect). */
  traceStreamRequest(conversationId: string, token?: string): { url: string; headers: Record<string, string> } {
    const params = new URLSearchParams({ conversationId })
    return {
      url: `${this.base}/trace/stream?${params.toString()}`,
      headers: {
        Accept: 'text/event-stream',
        ...this.authHeaders(token),
      },
    }
  }
}

/** Factory helper mirroring the class constructor. */
export function createGatewayClient(options?: GatewayClientOptions): GatewayClient {
  return new GatewayClient(options)
}
