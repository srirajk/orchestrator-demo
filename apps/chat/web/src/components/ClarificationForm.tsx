import { useState } from 'react'
import type { StructuredInteraction } from '../lib/gatewayTrace'

export interface ClarificationSubmit {
  selection?: string
  freeText?: string
}

interface ClarificationFormProps {
  interaction: StructuredInteraction
  disabled?: boolean
  onSubmit: (payload: ClarificationSubmit) => void
}

/**
 * Renders a structured-clarification form inline in the chat, driven ENTIRELY by the
 * `structured_interaction` event payload — the gateway authored every label/option/prompt from the
 * manifest + coverage book, so this component holds ZERO domain copy (World B). It never shows a
 * hidden-candidate count (an enumeration oracle): the options are exactly what the payload carries.
 *
 * <p>A {@code blocking} interaction (it replaced a no-service answer) renders as a card with the
 * offered choices; a non-blocking one (refinement chips beside an already-streamed answer) renders as
 * a lighter chip row. Both always offer the free-text escape so the user is never trapped in the set.
 * On submit the parent re-drives the resume (POST /api/clarify/resolve) and streams the answer back.
 */
export function ClarificationForm({ interaction, disabled = false, onSubmit }: ClarificationFormProps) {
  const [freeText, setFreeText] = useState('')
  const options = interaction.options ?? []
  const escape = interaction.freeText
  const blocking = interaction.blocking !== false
  const question = interaction.question?.trim()

  function submitFreeText() {
    const value = freeText.trim()
    if (!value || disabled) return
    onSubmit({ freeText: value })
  }

  return (
    <div
      role="group"
      aria-label="Clarification"
      data-testid="clarification-form"
      data-blocking={blocking}
      className={
        blocking
          ? 'mx-4 my-3 rounded-lg border border-line bg-panel p-4 shadow-sm'
          : 'mx-4 my-3 rounded-lg border border-dashed border-line bg-transparent p-3'
      }
    >
      {question && (
        <p className={blocking ? 'mb-3 text-sm font-medium text-fg' : 'mb-2 text-xs font-medium text-muted'}>
          {question}
        </p>
      )}

      {options.length > 0 && (
        <div className={blocking ? 'flex flex-col gap-2' : 'flex flex-wrap gap-2'}>
          {options.map((opt) => (
            <button
              key={opt.value}
              type="button"
              disabled={disabled}
              onClick={() => onSubmit({ selection: opt.value })}
              className={
                blocking
                  ? 'flex items-center justify-between gap-3 rounded-md border border-line bg-bg px-3 py-2 text-left text-sm text-fg transition hover:border-accent hover:bg-accent/5 disabled:opacity-50'
                  : 'rounded-full border border-line bg-bg px-3 py-1 text-xs text-fg transition hover:border-accent hover:bg-accent/5 disabled:opacity-50'
              }
            >
              <span className="truncate">{opt.label}</span>
              {opt.secondaryLabel && blocking && (
                <span className="shrink-0 text-xs text-muted">{opt.secondaryLabel}</span>
              )}
            </button>
          ))}
        </div>
      )}

      {escape?.enabled && (
        <form
          className={options.length > 0 ? 'mt-3 flex items-center gap-2' : 'flex items-center gap-2'}
          onSubmit={(e) => {
            e.preventDefault()
            submitFreeText()
          }}
        >
          <input
            type="text"
            value={freeText}
            disabled={disabled}
            onChange={(e) => setFreeText(e.target.value)}
            placeholder={escape.prompt ?? ''}
            aria-label={escape.prompt ?? 'Type your own answer'}
            className="flex-1 rounded-md border border-line bg-bg px-3 py-2 text-sm text-fg placeholder:text-muted focus:border-accent focus:outline-none disabled:opacity-50"
          />
          <button
            type="submit"
            disabled={disabled || !freeText.trim()}
            className="shrink-0 rounded-md bg-accent px-3 py-2 text-sm font-medium text-white transition hover:opacity-90 disabled:opacity-40"
          >
            Send
          </button>
        </form>
      )}
    </div>
  )
}
