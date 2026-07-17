import { useState, useRef, useCallback, useMemo, useEffect } from 'react'
import { Info, ShieldX } from 'lucide-react'
import { useParams, useNavigate } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import { MessageList } from './MessageList'
import { Composer } from './Composer'
import { TraceRail } from './TraceRail'
import { ClarificationForm } from './ClarificationForm'
import type { ClarificationSubmit } from './ClarificationForm'
import { useConversationDetail, useCreateConversation } from '../hooks/useConversations'
import { useTraceStream, selectAccessNotice, selectPipelineStage } from '../hooks/useTraceStream'
import { apiStream } from '../api/client'
import { iterateSseData, selectStructuredInteraction } from '../lib/gatewayTrace'
import type { Message, MessageClientTiming } from '../api/types'

const PENDING_SEND_KEY = 'conduit.chat.pendingSend'
const PENDING_SEND_MAX_AGE_MS = 10 * 60 * 1000

interface PendingSend {
  conversationId: string | null
  content: string
  createdAt: number
}

type LocalMessage = Message & {
  optimistic?: {
    baseCount: number
  }
}

function savePendingSend(conversationId: string | null, content: string) {
  const pending: PendingSend = { conversationId, content, createdAt: Date.now() }
  window.sessionStorage.setItem(PENDING_SEND_KEY, JSON.stringify(pending))
}

function clearPendingSend(conversationId: string | null, content: string) {
  const pending = readPendingSend()
  if (pending?.conversationId === conversationId && pending.content === content) {
    window.sessionStorage.removeItem(PENDING_SEND_KEY)
  }
}

function readPendingSend(): PendingSend | null {
  const raw = window.sessionStorage.getItem(PENDING_SEND_KEY)
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw) as PendingSend
    if (!parsed.content || Date.now() - parsed.createdAt > PENDING_SEND_MAX_AGE_MS) {
      window.sessionStorage.removeItem(PENDING_SEND_KEY)
      return null
    }
    return parsed
  } catch {
    window.sessionStorage.removeItem(PENDING_SEND_KEY)
    return null
  }
}

function takePendingSend(conversationId: string | null): string | null {
  const pending = readPendingSend()
  if (pending?.conversationId !== conversationId) return null
  window.sessionStorage.removeItem(PENDING_SEND_KEY)
  return pending.content
}

function normalizedContent(content: string): string {
  return content.trim().replace(/\s+/g, ' ')
}

function sameMessageContent(a: Message, b: Message): boolean {
  return a.role === b.role && normalizedContent(a.content) === normalizedContent(b.content)
}

function serverConfirmsLocalMessage(
  serverMessages: Message[],
  localMessage: LocalMessage,
): boolean {
  const baseCount = localMessage.optimistic?.baseCount ?? 0
  return serverMessages.some((serverMessage, index) =>
    index >= baseCount && sameMessageContent(serverMessage, localMessage),
  )
}

function localTwinForServerMessage(
  localMessages: LocalMessage[],
  serverMessage: Message,
  serverIndex: number,
): LocalMessage | undefined {
  return localMessages.find((localMessage) => {
    const baseCount = localMessage.optimistic?.baseCount ?? 0
    return serverIndex >= baseCount && sameMessageContent(serverMessage, localMessage)
  })
}

export function ChatPane() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const qc = useQueryClient()

  const isNew = !id || id === 'new'

  const { data, isLoading } = useConversationDetail(isNew ? undefined : id)

  const [localMessages, setLocalMessages] = useState<LocalMessage[]>([])
  const [streamingContent, setStreamingContent] = useState<string | null>(null)
  const [streamingTiming, setStreamingTiming] = useState<MessageClientTiming | null>(null)
  const [isStreaming, setIsStreaming] = useState(false)
  const [railCollapsed, setRailCollapsed] = useState(false)
  const [restoredDraft, setRestoredDraft] = useState<{ id: number; content: string } | null>(null)
  const abortRef = useRef<AbortController | null>(null)
  const preserveNextIdRef = useRef<string | null>(null)

  // Track the server message count at send time so we know when the server has confirmed
  // the exchange and local optimistic messages can be safely cleared.
  const sendBaseCountRef = useRef(0)

  // Always-current server message count readable without closure staleness.
  const serverMessages = data?.messages ?? []
  const serverMsgCountRef = useRef(serverMessages.length)
  serverMsgCountRef.current = serverMessages.length

  // Glass-box: subscribe to this conversation's live authorization/pipeline trace.
  const { events: traceEvents, status: traceStatus } = useTraceStream(isNew ? undefined : id)
  const accessNotice = useMemo(() => selectAccessNotice(traceEvents), [traceEvents])
  const pipelineStage = useMemo(
    () => (isStreaming && streamingContent === '' ? selectPipelineStage(traceEvents) : null),
    [isStreaming, streamingContent, traceEvents],
  )
  const showAccessNotice = !!accessNotice && !(isStreaming && streamingContent === '')

  // The active structured-clarification form for this turn (a `structured_interaction` OOB event), or
  // null. Shown inline below the messages while no turn is streaming; submitting it re-drives the resume.
  const activeClarification = useMemo(() => selectStructuredInteraction(traceEvents), [traceEvents])

  const createConversation = useCreateConversation()

  useEffect(() => {
    const content = takePendingSend(isNew ? null : (id ?? null))
    if (content) {
      setRestoredDraft({ id: Date.now(), content })
    }
  }, [id, isNew])

  useEffect(() => {
    return () => {
      abortRef.current?.abort()
    }
  }, [])

  // Reset local state and kill the active stream when the user switches conversations.
  // The one exception is the internal /c/new -> /c/{createdId} transition during send:
  // that route change belongs to the same in-flight turn, so preserving local state keeps
  // the optimistic user bubble and assistant stream visible until the server copy arrives.
  useEffect(() => {
    if (id && preserveNextIdRef.current === id) {
      preserveNextIdRef.current = null
      return
    }
    abortRef.current?.abort()
    setLocalMessages([])
    setStreamingContent(null)
    setStreamingTiming(null)
    setIsStreaming(false)
  }, [id])

  // Drop confirmed local user bubbles as soon as their server twin arrives, but keep confirmed
  // assistant locals around as hidden timing metadata for the just-rendered server message.
  useEffect(() => {
    setLocalMessages((prev) => {
      const next = prev.filter((localMessage) => {
        const confirmed = serverConfirmsLocalMessage(serverMessages, localMessage)
        return !confirmed || (localMessage.role === 'assistant' && localMessage.clientTiming)
      })
      return next.length === prev.length ? prev : next
    })
    if (!isStreaming && serverMessages.length >= sendBaseCountRef.current + 2) {
      sendBaseCountRef.current = serverMessages.length
    }
  }, [serverMessages, isStreaming])

  // Merge server messages with local optimistic messages, de-duplicating by turn content.
  // The BFF persists the user row before the assistant row, so a server user confirmation alone
  // must not remove the local assistant answer that just finished streaming.
  const messages = useMemo(() => {
    const enrichedServerMessages = serverMessages.map((serverMessage, index) => {
      const localTwin = localTwinForServerMessage(localMessages, serverMessage, index)
      return localTwin?.clientTiming
        ? { ...serverMessage, clientTiming: localTwin.clientTiming }
        : serverMessage
    })
    const visibleLocalMessages = localMessages.filter(
      (localMessage) => !serverConfirmsLocalMessage(serverMessages, localMessage),
    )
    return [...enrichedServerMessages, ...visibleLocalMessages]
  }, [serverMessages, localMessages])

  // Shared streaming pump: consume an OpenAI-shaped SSE response, render the deltas live, and finalize
  // the assistant turn. Reused by an ordinary send AND a clarification resume so both render identically.
  const pumpAssistantStream = useCallback(
    async (convId: string, resp: Response, baseCount: number, startedAt: number) => {
      let ttftMs: number | undefined

      const finalize = (accumulated: string) => {
        const timing: MessageClientTiming = {
          ...(ttftMs !== undefined ? { ttftMs } : {}),
          totalMs: performance.now() - startedAt,
        }
        const assistantMsg: LocalMessage = {
          id: `local-assistant-${Date.now()}`,
          role: 'assistant',
          content: accumulated,
          createdAt: new Date().toISOString(),
          clientTiming: timing,
          optimistic: { baseCount },
        }
        setLocalMessages((prev) => [...prev, assistantMsg])
      }

      try {
        if (!resp.body) throw new Error('Response body is null')
        let accumulated = ''
        for await (const data of iterateSseData(resp.body)) {
          if (data === '[DONE]') {
            finalize(accumulated)
            return
          }
          try {
            const parsed = JSON.parse(data) as {
              choices?: Array<{ delta?: { content?: string } }>
            }
            const delta = parsed.choices?.[0]?.delta?.content ?? ''
            if (delta) {
              if (ttftMs === undefined) {
                ttftMs = performance.now() - startedAt
                setStreamingTiming({ ttftMs })
              }
              accumulated += delta
              setStreamingContent(accumulated)
            }
          } catch {
            // Ignore malformed lines
          }
        }
        // Reached the end without an explicit [DONE] — finalize with what we have.
        finalize(accumulated)
      } catch (err) {
        if ((err as Error).name === 'AbortError') {
          console.info('Stream aborted')
        } else {
          console.error('Stream error', err)
        }
      } finally {
        setStreamingContent(null)
        setStreamingTiming(null)
        setIsStreaming(false)
        void qc.invalidateQueries({ queryKey: ['conversation', convId] })
        void qc.invalidateQueries({ queryKey: ['conversations'] })
      }
    },
    [qc],
  )

  const handleSend = useCallback(
    async (content: string) => {
      let convId = isNew ? null : (id ?? null)
      savePendingSend(convId, content)

      // Create conversation if new
      if (!convId) {
        try {
          const conv = await createConversation.mutateAsync({
            title: content.slice(0, 60),
          })
          convId = conv.id
          savePendingSend(convId, content)
          clearPendingSend(convId, content)
          preserveNextIdRef.current = conv.id
          navigate(`/c/${convId}`, { replace: true })
        } catch (err) {
          console.error('Failed to create conversation', err)
          return
        }
      }
      clearPendingSend(convId, content)

      // Snapshot server message count before adding optimistic messages so we can detect
      // when the server has caught up (Fix: bug 1 — use ref so no stale-closure risk).
      const baseCount = serverMsgCountRef.current
      sendBaseCountRef.current = baseCount

      // Optimistically append user message
      const userMsg: LocalMessage = {
        id: `local-${Date.now()}`,
        role: 'user',
        content,
        createdAt: new Date().toISOString(),
        optimistic: { baseCount },
      }
      setLocalMessages((prev) => [...prev, userMsg])

      // Start streaming
      setIsStreaming(true)
      setStreamingContent('')
      setStreamingTiming(null)

      abortRef.current = new AbortController()
      const startedAt = performance.now()

      let resp: Response
      try {
        resp = await apiStream(`/api/conversations/${convId}/messages`, {
          method: 'POST',
          body: JSON.stringify({ content }),
          headers: { 'Content-Type': 'application/json' },
          signal: abortRef.current.signal,
        })
      } catch (err) {
        if ((err as Error).name !== 'AbortError') console.error('Send error', err)
        setStreamingContent(null)
        setStreamingTiming(null)
        setIsStreaming(false)
        return
      }
      clearPendingSend(convId, content)
      await pumpAssistantStream(convId, resp, baseCount, startedAt)
    },
    [id, isNew, navigate, createConversation, qc, pumpAssistantStream]
  )

  // Resume a structured clarification: fold the chosen option / free text into a user turn and re-drive
  // the gateway (POST /api/clarify/resolve), then stream the resumed answer back exactly like a send. The
  // gateway consumes the single-use nonce and re-runs the full pipeline (entitlement re-CHECKed).
  const handleClarifyResolve = useCallback(
    async (nonce: string, payload: ClarificationSubmit) => {
      const convId = isNew ? null : (id ?? null)
      if (!convId) return
      const folded = (payload.freeText ?? payload.selection ?? '').trim()
      if (!folded) return

      const baseCount = serverMsgCountRef.current
      sendBaseCountRef.current = baseCount

      const userMsg: LocalMessage = {
        id: `local-${Date.now()}`,
        role: 'user',
        content: folded,
        createdAt: new Date().toISOString(),
        optimistic: { baseCount },
      }
      setLocalMessages((prev) => [...prev, userMsg])

      setIsStreaming(true)
      setStreamingContent('')
      setStreamingTiming(null)

      abortRef.current = new AbortController()
      const startedAt = performance.now()

      let resp: Response
      try {
        resp = await apiStream('/api/clarify/resolve', {
          method: 'POST',
          body: JSON.stringify({
            conversationId: convId,
            nonce,
            ...(payload.selection ? { selection: payload.selection } : {}),
            ...(payload.freeText ? { freeText: payload.freeText } : {}),
          }),
          headers: { 'Content-Type': 'application/json' },
          signal: abortRef.current.signal,
        })
      } catch (err) {
        if ((err as Error).name !== 'AbortError') console.error('Clarify resolve error', err)
        setStreamingContent(null)
        setStreamingTiming(null)
        setIsStreaming(false)
        return
      }
      await pumpAssistantStream(convId, resp, baseCount, startedAt)
    },
    [id, isNew, pumpAssistantStream]
  )

  function handleStop() {
    abortRef.current?.abort()
  }

  const conversationTitle = data?.conversation.title ?? (isNew ? 'New Chat' : 'Loading…')

  return (
    <div className="flex h-full overflow-hidden">
      <div className="flex flex-col flex-1 min-w-0 overflow-hidden">
        {/* Header */}
        <div className="border-b border-line bg-panel px-6 py-3 flex items-center gap-3">
          <h1 className="section-heading truncate">{conversationTitle}</h1>
        </div>

        {/* Access notice — full denial is an alert; partial access is informational. */}
        {showAccessNotice && (
          <div
            role={accessNotice.kind === 'full-denial' ? 'alert' : 'status'}
            className={
              accessNotice.kind === 'full-denial'
                ? 'mx-4 mt-3 flex items-start gap-2 rounded-md border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800'
                : 'mx-4 mt-3 flex items-start gap-2 rounded-md border border-sky-200 bg-sky-50 px-4 py-3 text-sm text-sky-900'
            }
          >
            {accessNotice.kind === 'full-denial' ? (
              <ShieldX size={18} className="mt-0.5 shrink-0 text-red-600" />
            ) : (
              <Info size={18} className="mt-0.5 shrink-0 text-sky-600" />
            )}
            <p>
              <span className="font-semibold">{accessNotice.title}</span>
              <span>: {accessNotice.message}</span>
            </p>
          </div>
        )}

        {/* Messages */}
        <MessageList
          messages={messages}
          streamingContent={streamingContent}
          streamingTiming={streamingTiming}
          pipelineStage={pipelineStage}
          isLoading={!isNew && isLoading}
        />

        {/* Structured clarification form — rendered inline where the assistant turn would be, driven
            entirely by the OOB `structured_interaction` event payload (World B: no domain copy here).
            Hidden while a turn is streaming so it never overlaps a live answer. */}
        {activeClarification && !isStreaming && (
          <ClarificationForm
            interaction={activeClarification}
            disabled={isStreaming}
            onSubmit={(payload) => handleClarifyResolve(activeClarification.nonce, payload)}
          />
        )}

        {/* Composer */}
        <Composer
          onSend={handleSend}
          isStreaming={isStreaming}
          onStop={handleStop}
          restoredDraft={restoredDraft}
        />
      </div>

      {/* Glass-box decision trace rail (collapsible) */}
      <TraceRail
        events={traceEvents}
        status={traceStatus}
        collapsed={railCollapsed}
        onToggle={() => setRailCollapsed((c) => !c)}
      />
    </div>
  )
}
