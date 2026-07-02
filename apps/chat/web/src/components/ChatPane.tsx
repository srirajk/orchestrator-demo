import { useState, useRef, useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import { MessageList } from './MessageList'
import { Composer } from './Composer'
import { useConversationDetail, useCreateConversation } from '../hooks/useConversations'
import { apiStream } from '../api/client'
import type { Message } from '../api/types'

export function ChatPane() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const qc = useQueryClient()

  const isNew = !id || id === 'new'

  const { data, isLoading } = useConversationDetail(isNew ? undefined : id)

  const [localMessages, setLocalMessages] = useState<Message[]>([])
  const [streamingContent, setStreamingContent] = useState<string | null>(null)
  const [isStreaming, setIsStreaming] = useState(false)
  const abortRef = useRef<AbortController | null>(null)

  const createConversation = useCreateConversation()

  // Merge server messages with local optimistic messages
  const serverMessages = data?.messages ?? []
  // When we have server data, prefer it; local messages are only used while pending
  const messages = serverMessages.length > 0 ? serverMessages : localMessages

  const handleSend = useCallback(
    async (content: string) => {
      let convId = isNew ? null : (id ?? null)

      // Create conversation if new
      if (!convId) {
        try {
          const conv = await createConversation.mutateAsync({
            title: content.slice(0, 60),
          })
          convId = conv.id
          navigate(`/c/${convId}`, { replace: true })
        } catch (err) {
          console.error('Failed to create conversation', err)
          return
        }
      }

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

        if (!resp.body) {
          throw new Error('Response body is null')
        }

        const reader = resp.body.getReader()
        const decoder = new TextDecoder()
        let accumulated = ''
        let buffer = ''

        while (true) {
          const { done, value } = await reader.read()
          if (done) break

          buffer += decoder.decode(value, { stream: true })
          const lines = buffer.split('\n')
          // Keep the last partial line in buffer
          buffer = lines.pop() ?? ''

          for (const line of lines) {
            if (!line.startsWith('data: ')) continue
            const data = line.slice(6).trim()
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
          // Stopped by user — keep whatever was accumulated
          console.info('Stream aborted by user')
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
    <div className="flex flex-col h-full overflow-hidden">
      {/* Header */}
      <div className="border-b border-line bg-panel px-6 py-3 flex items-center gap-3">
        <h1 className="section-heading truncate">{conversationTitle}</h1>
      </div>

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
      />
    </div>
  )
}
