/**
 * Gateway trace contract for the chat SPA — the self-contained, canonical definition of the
 * dependency-free SSE parser + glass-box trace types the gateway emits.
 *
 * Self-contained by design: the `conduit-chat` Docker image builds `apps/chat/web` with build
 * context `./apps/chat`, so nothing outside that dir can be imported by path. This module keeps
 * the SAME wire contract the gateway emits so the rail consumes it identically.
 */

// ── Trace wire types (mirror of gateway-client/src/types.ts) ─────────────────

export type GateName = 'audience' | 'segment' | 'classification' | 'coverage'
export type GateEffect = 'allow' | 'deny'

export interface TraceAgentRef {
  agentId?: string
  protocol?: string
  score?: number
}

export interface TraceFilteredRef {
  agentId?: string
  reason?: string
}

export interface TraceEventData extends Record<string, unknown> {
  userId?: string
  prompt?: string
  intent?: string
  confidence?: number
  reasoning?: string
  selected?: TraceAgentRef[]
  filtered?: TraceFilteredRef[]
  relationshipId?: string
  allowed?: boolean
  reason?: string
  source?: string
  agentId?: string
  protocol?: string
  durationMs?: number
  status?: string
  dataPreview?: string
  agentCount?: number
  successCount?: number
  totalMs?: number
  // `gate` frame fields — one authorization gate's structured pass/deny + reason.
  gate?: GateName
  effect?: GateEffect
  agent?: string
}

export interface TraceEvent {
  type: string
  requestId: string
  conversationId?: string | null
  timestamp: number
  data?: TraceEventData
}

// ── Structured clarification (the form payload) ──────────────────────────────
// Mirror of the gateway's StructuredInteraction record, ridden on the OOB trace lane as a
// `structured_interaction` event. Every field is DATA authored by the manifest/coverage — the SPA
// renders it blind (World B): it shows only what the payload carries and never a hidden-candidate count.

export interface StructuredInteractionOption {
  value: string
  label: string
  secondaryLabel?: string
}

export interface StructuredInteractionFreeText {
  enabled: boolean
  prompt?: string
  inputContract?: string
}

export interface StructuredInteraction {
  kind?: string
  nonce: string
  question?: string
  entityNoun?: string
  options: StructuredInteractionOption[]
  freeText?: StructuredInteractionFreeText
  clarifyDepth?: number
  maxClarifyDepth?: number
  atCap?: boolean
  /** true = a blocking form that REPLACES a no-service; false = non-blocking refinement chips beside an answer. */
  blocking?: boolean
}

export const STRUCTURED_INTERACTION_EVENT = 'structured_interaction'

/**
 * The active structured-clarification form for the current turn, or null. Scans the current turn's
 * frames for the LATEST `structured_interaction` (frames reset on `request_start`, so this is always
 * the form for the question in flight). A payload with no `nonce` is ignored (it cannot be resumed).
 * `options` is normalized to an array so a pure free-text ask still renders.
 */
export function selectStructuredInteraction(events: TraceEvent[]): StructuredInteraction | null {
  let latest: StructuredInteraction | null = null
  for (const evt of events) {
    if (evt.type !== STRUCTURED_INTERACTION_EVENT) continue
    const data = (evt.data ?? {}) as unknown as StructuredInteraction
    if (!data || typeof data.nonce !== 'string' || !data.nonce) continue
    latest = { ...data, options: Array.isArray(data.options) ? data.options : [] }
  }
  return latest
}

// ── SSE parsing (mirror of gateway-client/src/sse.ts) ────────────────────────

/** Extract the joined `data:` payload from one SSE event block, or null. */
export function sseDataFromBlock(block: string): string | null {
  const lines = block.split('\n')
  const dataLines = lines
    .filter((line) => line.startsWith('data:'))
    .map((line) => line.slice(5).trimStart())
  return dataLines.length > 0 ? dataLines.join('\n') : null
}

/** Split a buffer on SSE event boundaries (`\n\n`), returning the remainder. */
export function splitSseBlocks(buffer: string): { blocks: string[]; rest: string } {
  const normalized = buffer.replace(/\r\n/g, '\n')
  const blocks: string[] = []
  let rest = normalized
  let index = rest.indexOf('\n\n')

  while (index >= 0) {
    blocks.push(rest.slice(0, index))
    rest = rest.slice(index + 2)
    index = rest.indexOf('\n\n')
  }

  return { blocks, rest }
}

/**
 * Async iterator over the `data:` payloads of an SSE response body. Yields raw strings (callers
 * `JSON.parse` and/or check for a `[DONE]` sentinel). Completes when the stream ends.
 */
export async function* iterateSseData(
  body: ReadableStream<Uint8Array>,
): AsyncGenerator<string, void, unknown> {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const { blocks, rest } = splitSseBlocks(buffer)
      buffer = rest
      for (const block of blocks) {
        const data = sseDataFromBlock(block)
        if (data !== null) yield data
      }
    }
    const tail = sseDataFromBlock(buffer.replace(/\r\n/g, '\n'))
    if (tail !== null) yield tail
  } finally {
    reader.releaseLock()
  }
}
