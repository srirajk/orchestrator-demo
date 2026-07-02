import { useState, useRef, useEffect } from 'react'
import { Send, Square } from 'lucide-react'
import clsx from 'clsx'

interface Props {
  onSend: (content: string) => void
  isStreaming: boolean
  onStop: () => void
  disabled?: boolean
}

export function Composer({ onSend, isStreaming, onStop, disabled }: Props) {
  const [value, setValue] = useState('')
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  // Auto-grow
  useEffect(() => {
    const el = textareaRef.current
    if (!el) return
    el.style.height = 'auto'
    const scrollH = el.scrollHeight
    const maxH = 8 * 24 // ~8 lines at 24px line-height
    el.style.height = Math.min(scrollH, maxH) + 'px'
  }, [value])

  function handleKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      submit()
    }
  }

  function submit() {
    const trimmed = value.trim()
    if (!trimmed || isStreaming || disabled) return
    onSend(trimmed)
    setValue('')
    // Reset height
    if (textareaRef.current) textareaRef.current.style.height = 'auto'
  }

  const canSend = value.trim().length > 0 && !isStreaming && !disabled

  return (
    <div className="border-t border-line bg-panel px-4 py-3">
      <div className="flex items-end gap-3 surface-card px-4 py-3 rounded-xl shadow-sm">
        <textarea
          ref={textareaRef}
          rows={1}
          value={value}
          onChange={(e) => setValue(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder={isStreaming ? 'Waiting for response…' : 'Ask anything…'}
          disabled={isStreaming || disabled}
          className={clsx(
            'flex-1 resize-none bg-transparent text-sm text-ink-900 placeholder:text-ink-500',
            'outline-none leading-6 min-h-[24px] max-h-[192px] overflow-y-auto',
            (isStreaming || disabled) && 'opacity-50 cursor-not-allowed'
          )}
        />

        {isStreaming ? (
          <button
            onClick={onStop}
            className="shrink-0 flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-red-500 text-white text-xs font-semibold hover:bg-red-600 transition-colors"
          >
            <Square size={12} />
            Stop
          </button>
        ) : (
          <button
            onClick={submit}
            disabled={!canSend}
            className={clsx(
              'shrink-0 flex items-center justify-center h-8 w-8 rounded-lg transition-colors',
              canSend
                ? 'bg-gold-400 text-axiom-950 hover:bg-gold-300'
                : 'bg-axiom-100 text-axiom-400 cursor-not-allowed'
            )}
          >
            <Send size={14} />
          </button>
        )}
      </div>
      <p className="text-xs text-ink-500 text-center mt-2">
        Enter to send · Shift+Enter for newline
      </p>
    </div>
  )
}
