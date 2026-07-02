/**
 * @conduit/gateway-client — a small, typed, framework-agnostic client for the
 * Conduit gateway.
 *
 * - OpenAI-compatible `/v1/chat/completions` (streaming SSE + non-streaming)
 * - Glass-box `/trace/stream` (parsed TraceEvent stream)
 * - Admin manifest endpoints (`/admin/domains`, `/admin/agents`, `/trace/health`)
 *
 * Zero domain knowledge, zero framework dependency — just the gateway's
 * transport contract and its wire types.
 */
export { GatewayClient, createGatewayClient } from './client'
export type { GatewayClientOptions, ChatRequest, TraceStreamRequest } from './client'

export { iterateSseData, sseDataFromBlock, splitSseBlocks } from './sse'

export { eventTone, eventTitle, eventDetail } from './events'
export {
  domainId,
  domainName,
  coverageConfigured,
  agentId,
  subDomain,
  timeoutMs,
  classification,
  latestOf,
} from './selectors'
export { getString, getNumber, formatTime, formatDuration } from './format'

export type {
  ChatRole,
  ChatMessage,
  ChatCompletionResponse,
  ChatCompletionChunk,
  TraceAgentRef,
  TraceFilteredRef,
  TraceEventData,
  TraceEvent,
  TraceHealth,
  DomainManifest,
  DomainEntry,
  AgentManifest,
  StatusTone,
  EventTone,
} from './types'
