import { useEffect, useRef } from 'react'
import { MessageSquare } from 'lucide-react'
import { Message } from './Message'
import type { Message as MessageType, MessageClientTiming } from '../api/types'

function LoadingSkeleton() {
  return (
    <div className="flex gap-3 px-4 py-3 animate-pulse">
      <div className="h-7 w-7 rounded-full bg-axiom-100 shrink-0 mt-0.5" />
      <div className="flex-1 space-y-2 max-w-[60%]">
        <div className="h-3 bg-axiom-100 rounded w-3/4" />
        <div className="h-3 bg-axiom-100 rounded w-1/2" />
        <div className="h-3 bg-axiom-100 rounded w-2/3" />
      </div>
    </div>
  )
}

interface Props {
  messages: MessageType[]
  streamingContent: string | null
  streamingTiming?: MessageClientTiming | null
  isLoading?: boolean
}

export function MessageList({ messages, streamingContent, streamingTiming, isLoading }: Props) {
  const bottomRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages.length, streamingContent])

  if (isLoading) {
    return (
      <div className="flex-1 overflow-y-auto py-4">
        <LoadingSkeleton />
        <LoadingSkeleton />
        <LoadingSkeleton />
      </div>
    )
  }

  if (messages.length === 0 && streamingContent === null) {
    return (
      <div className="flex-1 flex flex-col items-center justify-center gap-4 text-center px-8">
        <div className="h-14 w-14 rounded-full bg-axiom-100 flex items-center justify-center">
          <MessageSquare size={24} className="text-axiom-400" />
        </div>
        <div>
          <p className="section-heading text-ink-700">Start a conversation</p>
          <p className="muted-copy mt-1">Ask anything across your business domains.</p>
        </div>
      </div>
    )
  }

  return (
    <div className="flex-1 overflow-y-auto py-2">
      {messages.map((msg) => (
        <Message key={msg.id} message={msg} />
      ))}

      {/* Streaming bubble */}
      {streamingContent !== null && (
        <Message
          message={{
            id: '__streaming__',
            role: 'assistant',
            content: streamingContent,
            createdAt: new Date().toISOString(),
            clientTiming: streamingTiming ?? undefined,
          }}
          isStreaming={true}
        />
      )}

      <div ref={bottomRef} />
    </div>
  )
}
