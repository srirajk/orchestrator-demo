import { useCallback, useEffect, useRef, useState } from 'react'
import { redirectToLogin } from '../api/client'
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
 * pipeline frames of the current turn. Uses the dependency-free SSE parser (`iterateSseData`)
 * and `TraceEvent` type from `../lib/gatewayTrace` — the same contract the gateway emits.
 *
 * <p>Uses `fetch` + a manual reader (not native `EventSource`) so it inherits the SPA session
 * cookie via `credentials: 'include'`. Frames accumulate until a new `request_start` frame, at
 * which point the list resets so the rail always reflects the latest question's decision.
 *
 * <p>The stream is kept alive across turns with an automatic reconnect loop with exponential
 * backoff (Fix: bug 5 — previously only the first turn showed a trace because the stream was
 * never reopened after the server closed it).
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
    let mounted = true

    void (async () => {
      let retryMs = 1_000
      while (mounted) {
        try {
          const resp = await fetch(`/api/conversations/${conversationId}/trace/stream`, {
            credentials: 'include',
            headers: { Accept: 'text/event-stream' },
            signal: ac.signal,
          })

          if (resp.status === 401) {
            return redirectToLogin()
          }

          if (!resp.ok || !resp.body) {
            setStatus('error')
            if (!mounted) break
            await new Promise<void>((r) => setTimeout(r, retryMs))
            retryMs = Math.min(retryMs * 2, 16_000)
            if (mounted) setStatus('connecting')
            continue
          }

          setStatus('open')
          retryMs = 1_000 // reset on successful connection

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

          // Stream ended naturally (server closed after sending a response) — pause briefly
          // then reconnect so the rail is ready for the next turn.
          if (mounted) {
            setStatus('connecting')
            await new Promise<void>((r) => setTimeout(r, 500))
          }
        } catch (err) {
          if ((err as Error).name === 'AbortError') break // intentional abort — stop looping
          setStatus('error')
          if (!mounted) break
          await new Promise<void>((r) => setTimeout(r, retryMs))
          retryMs = Math.min(retryMs * 2, 16_000)
          if (mounted) setStatus('connecting')
        }
      }
    })()

    return () => {
      mounted = false
      ac.abort()
    }
  }, [conversationId])

  return { events, status, clear }
}

export interface TraceAccessNotice {
  kind: 'full-denial' | 'partial-access'
  title: string
  message: string
}

/**
 * Derives the user-facing access notice for the current turn. Some routed agents may be denied
 * while another agent still answers; that is partial access, not a failed turn.
 */
export function selectAccessNotice(events: TraceEvent[]): TraceAccessNotice | null {
  let deniedDomain: string | null = null
  let answeredDomain: string | null = null
  let deniedRelationship = false
  let hasAnswer = false

  for (const evt of events) {
    const data = evt.data ?? {}
    if (
      (evt.type === 'request_complete' && Number(data.successCount ?? 0) > 0) ||
      (evt.type === 'synthesis_start' && Number(data.successCount ?? 0) > 0)
    ) {
      hasAnswer = true
    }
    if (evt.type === 'agent_complete' && String(data.status ?? '').toLowerCase() !== 'failed') {
      hasAnswer = true
      answeredDomain ??= humanDomain(data)
    }

    if (evt.type === 'gate' && data.effect === 'deny') {
      deniedDomain = humanDomain(data)
      deniedRelationship ||= data.gate === 'coverage'
    } else if (evt.type === 'entitlement_check' && data.allowed === false) {
      deniedDomain = humanDomain(data)
      deniedRelationship = true
    }
  }

  if (!deniedDomain && !deniedRelationship) return null

  if (hasAnswer) {
    const withheld = deniedDomain ?? 'some restricted data'
    const showing = answeredDomain ? `Showing ${answeredDomain}. ` : 'Showing the data you can access. '
    return {
      kind: 'partial-access',
      title: 'Partial access',
      message: `${showing}You don't have access to the ${withheld} for this client.`,
    }
  }

  return {
    kind: 'full-denial',
    title: 'Access denied',
    message: deniedRelationship
      ? "You don't have access to this client relationship."
      : `You don't have access to the ${deniedDomain ?? 'requested data'}.`,
  }
}

function humanDomain(data: Record<string, unknown>): string | null {
  const haystack = [
    data.domain,
    data.segment,
    data.agent,
    data.agentId,
    data.source,
    data.reason,
  ]
    .filter((value) => value !== undefined && value !== null)
    .map((value) => String(value).toLowerCase())
    .join(' ')

  if (!haystack) return null
  if (haystack.includes('asset-servicing') || haystack.includes('asset servicing')) {
    return 'asset-servicing (custody & settlement) data'
  }
  if (haystack.includes('wealth')) return 'wealth data'
  if (haystack.includes('insurance') || haystack.includes('underwriting')) return 'insurance data'
  if (haystack.includes('market-research') || haystack.includes('market research')) {
    return 'market research data'
  }
  if (haystack.includes('hr') || haystack.includes('policy')) return 'HR policy data'
  return null
}
