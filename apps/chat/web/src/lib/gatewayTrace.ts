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
