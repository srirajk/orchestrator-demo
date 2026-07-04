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

type DomainKey = 'asset-servicing' | 'wealth' | 'insurance' | 'market-research' | 'hr'

/**
 * Derives the user-facing access notice for the current turn. Some routed agents may be denied
 * while another agent still answers; that is partial access, not a failed turn.
 */
export function selectAccessNotice(events: TraceEvent[]): TraceAccessNotice | null {
  const deniedDomains = new Set<DomainKey>()
  const answeredDomains = new Set<DomainKey>()
  let promptDomain: DomainKey | null = null
  let deniedRelationship = false
  let hasAnswer = false

  for (const evt of events) {
    const data = evt.data ?? {}
    if (evt.type === 'request_start') {
      promptDomain = inferPromptDomain(String(data.prompt ?? '')) ?? promptDomain
    }
    if (
      (evt.type === 'request_complete' && Number(data.successCount ?? 0) > 0) ||
      (evt.type === 'synthesis_start' && Number(data.successCount ?? 0) > 0)
    ) {
      hasAnswer = true
    }
    if (evt.type === 'agent_complete' && String(data.status ?? '').toLowerCase() !== 'failed') {
      hasAnswer = true
      const domain = domainFromData(data)
      if (domain) answeredDomains.add(domain)
    }

    if (evt.type === 'gate' && data.effect === 'deny') {
      const domain = domainFromData(data)
      if (domain) deniedDomains.add(domain)
      deniedRelationship ||= data.gate === 'coverage'
    } else if (evt.type === 'entitlement_check' && data.allowed === false) {
      const domain = domainFromData(data)
      if (domain) deniedDomains.add(domain)
      deniedRelationship = true
    }
  }

  if (deniedDomains.size === 0 && !deniedRelationship) return null

  if (hasAnswer) {
    const relevantDeniedDomains = [...deniedDomains].filter((domain) =>
      promptDomain === null || promptDomain === domain || answeredDomains.has(domain),
    )
    if (relevantDeniedDomains.length === 0 && !deniedRelationship) return null

    const withheldDomain = relevantDeniedDomains[0] ?? [...deniedDomains][0] ?? null
    const answeredDomain = [...answeredDomains][0] ?? null
    const withheld = withheldDomain ? humanDomain(withheldDomain) : 'some restricted data'
    const showing = answeredDomain
      ? `Showing ${humanDomain(answeredDomain)}. `
      : 'Showing the data you can access. '
    return {
      kind: 'partial-access',
      title: 'Partial access',
      message: `${showing}You don't have access to the ${withheld} for this client.`,
    }
  }

  const deniedDomain = [...deniedDomains][0] ?? null
  return {
    kind: 'full-denial',
    title: 'Access denied',
    message: deniedRelationship
      ? "You don't have access to this client relationship."
      : `You don't have access to the ${deniedDomain ? humanDomain(deniedDomain) : 'requested data'}.`,
  }
}

function domainFromData(data: Record<string, unknown>): DomainKey | null {
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
    return 'asset-servicing'
  }
  if (haystack.includes('wealth')) return 'wealth'
  if (haystack.includes('insurance') || haystack.includes('underwriting')) return 'insurance'
  if (haystack.includes('market-research') || haystack.includes('market research')) {
    return 'market-research'
  }
  if (haystack.includes('hr') || haystack.includes('policy')) return 'hr'
  return null
}

function inferPromptDomain(prompt: string): DomainKey | null {
  const text = prompt.toLowerCase()
  if (!text) return null
  if (
    text.includes('settlement') ||
    text.includes('custody') ||
    text.includes('corporate action') ||
    text.includes('cash management') ||
    text.includes('nav') ||
    text.includes('sterling')
  ) {
    return 'asset-servicing'
  }
  if (
    text.includes('holding') ||
    text.includes('portfolio') ||
    text.includes('family office') ||
    text.includes('performance') ||
    text.includes('risk profile') ||
    text.includes('goal') ||
    text.includes('whitman')
  ) {
    return 'wealth'
  }
  if (
    text.includes('insurance') ||
    text.includes('underwriting') ||
    text.includes('policy') ||
    text.includes('claim') ||
    text.includes('okafor')
  ) {
    return 'insurance'
  }
  return null
}

function humanDomain(domain: DomainKey): string {
  switch (domain) {
    case 'asset-servicing':
      return 'asset-servicing (custody & settlement) data'
    case 'wealth':
      return 'wealth data'
    case 'insurance':
      return 'insurance data'
    case 'market-research':
      return 'market research data'
    case 'hr':
      return 'HR policy data'
  }
}
