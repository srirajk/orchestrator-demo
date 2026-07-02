import { useEffect, useMemo, useState } from 'react'
import { traceStreamRequest } from '../api'
import type { TraceEvent } from '../types'

const MAX_EVENTS = 160
const RECONNECT_BASE_MS = 2500
const RECONNECT_MAX_MS = 30000
const RECONNECT_MAX_ATTEMPTS = 8

function sseDataFromBlock(block: string): string | null {
  const lines = block.split('\n')
  const dataLines = lines
    .filter((line) => line.startsWith('data:'))
    .map((line) => line.slice(5).trimStart())
  return dataLines.length > 0 ? dataLines.join('\n') : null
}

function splitSseBlocks(buffer: string): { blocks: string[]; rest: string } {
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

export function useTraceStream(conversationId: string, authToken: string) {
  const [events, setEvents] = useState<TraceEvent[]>([])
  const [trackedRequestIds, setTrackedRequestIds] = useState<string[]>([])
  const [connected, setConnected] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    setEvents([])
    setTrackedRequestIds([])
  }, [conversationId, authToken])

  useEffect(() => {
    if (!authToken) {
      setConnected(false)
      setError(null)
      return
    }

    const abort = new AbortController()
    let cancelled = false
    let reconnectAttempts = 0
    let reconnectTimer: ReturnType<typeof setTimeout> | undefined

    function scheduleReconnect() {
      if (cancelled || reconnectAttempts >= RECONNECT_MAX_ATTEMPTS) return
      const delay = Math.min(
        RECONNECT_MAX_MS,
        RECONNECT_BASE_MS * Math.pow(2, reconnectAttempts),
      )
      reconnectAttempts += 1
      reconnectTimer = setTimeout(connect, delay)
    }

    async function connect() {
      try {
        const { url, headers } = traceStreamRequest(conversationId, authToken)

        // Use fetch instead of native EventSource because EventSource cannot attach
        // Authorization headers. The gateway currently permits trace reads, but this
        // keeps the UI ready for the hardened authenticated trace endpoint.
        const response = await fetch(url, { headers, signal: abort.signal })
        if (!response.ok || !response.body) {
          throw new Error(response.statusText || 'Trace stream unavailable')
        }

        setConnected(true)
        setError(null)
        reconnectAttempts = 0

        const reader = response.body.getReader()
        const decoder = new TextDecoder()
        let buffer = ''

        while (!cancelled) {
          const { done, value } = await reader.read()
          if (done) break

          buffer += decoder.decode(value, { stream: true })
          const parsed = splitSseBlocks(buffer)
          buffer = parsed.rest

          parsed.blocks.forEach((block) => {
            const data = sseDataFromBlock(block)
            if (!data) return

            try {
              const event = JSON.parse(data) as TraceEvent
              if (event.conversationId !== conversationId) return
              setError(null)
              setEvents((current) => [...current, event].slice(-MAX_EVENTS))
              setTrackedRequestIds((current) => {
                if (
                  event.conversationId === conversationId
                  || current.includes(event.requestId)
                ) {
                  const next = current.includes(event.requestId)
                    ? current
                    : [...current, event.requestId]
                  return next.slice(-MAX_EVENTS)
                }
                return current
              })
            } catch {
              setError('Trace event parse failed')
            }
          })
        }
      } catch (err) {
        if (!cancelled) {
          setConnected(false)
          setError(err instanceof Error ? err.message : 'Trace stream disconnected')
          scheduleReconnect()
        }
      }
    }

    connect()

    return () => {
      cancelled = true
      setConnected(false)
      abort.abort()
      if (reconnectTimer) clearTimeout(reconnectTimer)
    }
  }, [conversationId, authToken])

  const visibleEvents = useMemo(
    () => events.filter((event) =>
      event.conversationId === conversationId
      || trackedRequestIds.includes(event.requestId),
    ),
    [conversationId, events, trackedRequestIds],
  )

  return { connected, error, events, visibleEvents }
}
