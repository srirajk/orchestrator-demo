import { useCallback, useEffect, useRef, useState } from 'react'
import { iterateSseData } from '../lib/gatewayTrace'
import type { TraceEvent } from '../lib/gatewayTrace'

export type TraceStatus = 'idle' | 'connecting' | 'open' | 'error'

export interface UseTraceStream {
  /** Frames for the current turn (reset when a new `request_start` arrives). */
  events: TraceEvent[]
  status: TraceStatus
  clear: () => void
}

/**
 * Subscribes to the BFF-proxied glass-box trace stream for one conversation and yields the
 * pipeline frames of the current turn. Reuses `@conduit/gateway-client`'s dependency-free SSE
 * parser (`iterateSseData`) and `TraceEvent` type — the same contract the gateway emits.
 *
 * <p>Uses `fetch` + a manual reader (not native `EventSource`) so it inherits the SPA session
 * cookie via `credentials: 'include'`. Frames accumulate until a new `request_start` frame, at
 * which point the list resets so the rail always reflects the latest question's decision.
 */
export function useTraceStream(conversationId: string | undefined): UseTraceStream {
  const [events, setEvents] = useState<TraceEvent[]>([])
  const [status, setStatus] = useState<TraceStatus>('idle')
  const abortRef = useRef<AbortController | null>(null)

  const clear = useCallback(() => setEvents([]), [])

  useEffect(() => {
    if (!conversationId || conversationId === 'new') {
      setEvents([])
      setStatus('idle')
      return
    }

    setEvents([])
    setStatus('connecting')
    const ac = new AbortController()
    abortRef.current = ac

    void (async () => {
      try {
        const resp = await fetch(`/api/conversations/${conversationId}/trace/stream`, {
          credentials: 'include',
          headers: { Accept: 'text/event-stream' },
          signal: ac.signal,
        })
        if (!resp.ok || !resp.body) {
          setStatus('error')
          return
        }
        setStatus('open')
        for await (const data of iterateSseData(resp.body)) {
          if (!data || data === '[DONE]') continue
          let evt: TraceEvent
          try {
            evt = JSON.parse(data) as TraceEvent
          } catch {
            continue // heartbeat comment or malformed frame
          }
          if (!evt || typeof evt.type !== 'string') continue
          setEvents((prev) =>
            evt.type === 'request_start' ? [evt] : [...prev, evt],
          )
        }
      } catch (err) {
        if ((err as Error).name !== 'AbortError') setStatus('error')
      }
    })()

    return () => ac.abort()
  }, [conversationId])

  return { events, status, clear }
}

export interface TraceDenial {
  gate: string
  reason: string
}

/**
 * Derives the access-denied state for the current turn from its trace frames: the last `gate`
 * frame with {@code effect === 'deny'} (or an `entitlement_check` that was not allowed). Returns
 * null when nothing was denied. Drives the explicit "Access denied → gate: reason" banner.
 */
export function selectDenial(events: TraceEvent[]): TraceDenial | null {
  let denial: TraceDenial | null = null
  for (const evt of events) {
    const data = evt.data ?? {}
    if (evt.type === 'gate' && data.effect === 'deny') {
      denial = { gate: String(data.gate ?? 'authorization'), reason: String(data.reason ?? '') }
    } else if (evt.type === 'entitlement_check' && data.allowed === false) {
      denial = {
        gate: 'coverage',
        reason: String(data.reason ?? `not authorized for ${String(data.relationshipId ?? 'this resource')}`),
      }
    }
  }
  return denial
}
