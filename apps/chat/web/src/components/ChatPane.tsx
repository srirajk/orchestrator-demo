import { useState, useRef, useCallback, useMemo, useEffect } from 'react'
import { Info, ShieldX } from 'lucide-react'
import { useParams, useNavigate } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import { MessageList } from './MessageList'
import { Composer } from './Composer'
import { TraceRail } from './TraceRail'
import { useConversationDetail, useCreateConversation } from '../hooks/useConversations'
import { useTraceStream, selectAccessNotice } from '../hooks/useTraceStream'
import { apiStream } from '../api/client'
import { iterateSseData } from '../lib/gatewayTrace'
import type { Message } from '../api/types'

const PENDING_SEND_KEY = 'conduit.chat.pendingSend'
const PENDING_SEND_MAX_AGE_MS = 10 * 60 * 1000

interface PendingSend {
  conversationId: string | null
  content: string
  createdAt: number
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

export function ChatPane() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const qc = useQueryClient()

  const isNew = !id || id === 'new'

  const { data, isLoading } = useConversationDetail(isNew ? undefined : id)

  const [localMessages, setLocalMessages] = useState<Message[]>([])
  const [streamingContent, setStreamingContent] = useState<string | null>(null)
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
    setIsStreaming(false)
  }, [id])

  // Once the server message count exceeds what it was at send time and we are no longer
  // streaming, the server has confirmed the exchange — clear local optimistic messages so they
  // don't appear as duplicates alongside the real server messages (Fix: bug 1).
  useEffect(() => {
    if (!isStreaming && serverMessages.length > sendBaseCountRef.current) {
      setLocalMessages([])
      sendBaseCountRef.current = serverMessages.length
    }
  }, [serverMessages.length, isStreaming])

  // Merge server messages with local optimistic messages, de-duplicating by id.
  // Local ids are "local-*" so they never collide with server UUIDs. This ensures the
  // optimistic user message is always visible while the assistant reply streams in, even
  // when the conversation already has prior server messages (length-based either/or was the bug).
  const messages = useMemo(() => {
    const serverIds = new Set(serverMessages.map((m) => m.id))
    return [...serverMessages, ...localMessages.filter((m) => !serverIds.has(m.id))]
  }, [serverMessages, localMessages])

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
          preserveNextIdRef.current = conv.id
          navigate(`/c/${convId}`, { replace: true })
        } catch (err) {
          console.error('Failed to create conversation', err)
          return
        }
      }

      // Snapshot server message count before adding optimistic messages so we can detect
      // when the server has caught up (Fix: bug 1 — use ref so no stale-closure risk).
      sendBaseCountRef.current = serverMsgCountRef.current

      // Optimistically append user message
      const userMsg: Message = {
        id: `local-${Date.now()}`,
        role: 'user',
        content,
        createdAt: new Date().toISOString(),
      }
      setLocalMessages((prev) => [...prev, userMsg])

      // Start streaming
      setIsStreaming(true)
      setStreamingContent('')

      abortRef.current = new AbortController()

      try {
        const resp = await apiStream(`/api/conversations/${convId}/messages`, {
          method: 'POST',
          body: JSON.stringify({ content }),
          headers: { 'Content-Type': 'application/json' },
          signal: abortRef.current.signal,
        })
        clearPendingSend(convId, content)

        if (!resp.body) {
          throw new Error('Response body is null')
        }

        let accumulated = ''

        // Use iterateSseData (from gatewayTrace) — it handles the decoder flush and the
        // trailing buffer after the stream ends, so the final token and [DONE] sentinel are
        // never silently dropped (Fix: bug 4 — trailing SSE buffer).
        for await (const data of iterateSseData(resp.body)) {
          if (data === '[DONE]') {
            // Finalize
            const assistantMsg: Message = {
              id: `local-assistant-${Date.now()}`,
              role: 'assistant',
              content: accumulated,
              createdAt: new Date().toISOString(),
            }
            setLocalMessages((prev) => [...prev, assistantMsg])
            setStreamingContent(null)
            setIsStreaming(false)
            // Invalidate to pull real messages from server
            void qc.invalidateQueries({ queryKey: ['conversation', convId] })
            void qc.invalidateQueries({ queryKey: ['conversations'] })
            return
          }
          try {
            const parsed = JSON.parse(data) as {
              choices?: Array<{ delta?: { content?: string } }>
            }
            const delta = parsed.choices?.[0]?.delta?.content ?? ''
            if (delta) {
              accumulated += delta
              setStreamingContent(accumulated)
            }
          } catch {
            // Ignore malformed lines
          }
        }

        // If we reach here without [DONE], finalize with what we have
        const assistantMsg: Message = {
          id: `local-assistant-${Date.now()}`,
          role: 'assistant',
          content: accumulated,
          createdAt: new Date().toISOString(),
        }
        setLocalMessages((prev) => [...prev, assistantMsg])
      } catch (err) {
        if ((err as Error).name === 'AbortError') {
          // Stopped by user or id change — keep whatever was accumulated
          console.info('Stream aborted')
        } else {
          console.error('Stream error', err)
        }
      } finally {
        setStreamingContent(null)
        setIsStreaming(false)
        void qc.invalidateQueries({ queryKey: ['conversation', convId] })
        void qc.invalidateQueries({ queryKey: ['conversations'] })
      }
    },
    [id, isNew, navigate, createConversation, qc]
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
        {accessNotice && (
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
          isLoading={!isNew && isLoading}
        />

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
