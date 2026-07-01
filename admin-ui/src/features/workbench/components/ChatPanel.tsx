import { FormEvent, useEffect, useRef } from 'react'
import { MessageSquare, Send } from 'lucide-react'
import { clsx } from 'clsx'
import { Badge } from '../../../components/ui/Badge'
import { Button } from '../../../components/ui/Button'
import type { WorkbenchMessage } from '../types'
import { Panel } from './Panel'

interface ChatPanelProps {
  messages: WorkbenchMessage[]
  draft: string
  isSending: boolean
  onDraftChange: (value: string) => void
  onSubmit: (event: FormEvent) => void
}

export function ChatPanel({
  messages,
  draft,
  isSending,
  onDraftChange,
  onSubmit,
}: ChatPanelProps) {
  const scrollRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' })
  }, [messages, isSending])

  return (
    <Panel title="Chat" icon={MessageSquare} action={<Badge color="yellow">MVP non-streaming</Badge>}>
      <div ref={scrollRef} className="h-[410px] overflow-y-auto px-4 py-4 bg-slate-50/70">
        {messages.length === 0 ? (
          <div className="h-full flex items-center justify-center">
            <div className="text-center max-w-sm">
              <div className="mx-auto mb-3 h-10 w-10 rounded-md bg-axiom-50 text-axiom-700 ring-1 ring-axiom-600/10 flex items-center justify-center">
                <MessageSquare size={18} />
              </div>
              <p className="text-sm font-medium text-slate-800">Ready</p>
              <p className="text-xs text-slate-500 mt-1">Workbench turns appear here.</p>
            </div>
          </div>
        ) : (
          <div className="space-y-3">
            {messages.map((message) => (
              <div
                key={message.id}
                className={clsx('flex', message.role === 'user' ? 'justify-end' : 'justify-start')}
              >
                <div className={clsx(
                  'max-w-[82%] rounded-lg px-3 py-2 text-sm shadow-sm border',
                  message.role === 'user'
                    ? 'bg-axiom-900 text-white border-gold-400/50'
                    : message.status === 'error'
                      ? 'bg-red-50 text-red-800 border-red-200'
                      : 'bg-white text-slate-800 border-slate-200',
                )}>
                  <p className="whitespace-pre-wrap leading-6">{message.content}</p>
                </div>
              </div>
            ))}
            {isSending && (
              <div className="max-w-[82%] rounded-lg px-3 py-2 text-sm bg-white border border-slate-200 shadow-sm">
                <div className="flex items-center gap-2 text-slate-500">
                  <span className="h-2 w-2 rounded-full bg-gold-500 animate-pulse" />
                  Running
                </div>
              </div>
            )}
          </div>
        )}
      </div>

      <form onSubmit={onSubmit} className="border-t border-slate-200 p-3 bg-white">
        <div className="flex items-end gap-2">
          <textarea
            value={draft}
            onChange={(event) => onDraftChange(event.target.value)}
            rows={3}
            className="flex-1 resize-none rounded-md border border-line px-3 py-2 text-sm text-ink-900 placeholder:text-slate-400 shadow-sm focus:border-axiom-700 focus:outline-none focus:ring-2 focus:ring-gold-300"
            placeholder="Ask a gateway question"
          />
          <Button type="submit" disabled={!draft.trim()} loading={isSending} className="h-10 px-3">
            <Send size={15} />
            Send
          </Button>
        </div>
      </form>
    </Panel>
  )
}
