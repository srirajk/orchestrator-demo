import { FormEvent, useState } from 'react'
import { workbenchApi } from '../api'
import type { WorkbenchMessage } from '../types'

function messageId(role: string): string {
  return `m-${Date.now()}-${role}-${Math.random().toString(36).slice(2, 7)}`
}

export function useWorkbenchChat() {
  const [conversationId] = useState(() => `workbench-${Date.now().toString(36)}`)
  const [draft, setDraft] = useState('')
  const [messages, setMessages] = useState<WorkbenchMessage[]>([])
  const [isSending, setIsSending] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    const content = draft.trim()
    if (!content || isSending) return

    const nextUserMessage: WorkbenchMessage = {
      id: messageId('user'),
      role: 'user',
      content,
    }
    const nextMessages = [...messages, nextUserMessage]

    setMessages(nextMessages)
    setDraft('')
    setIsSending(true)

    try {
      const response = await workbenchApi.sendChatTurnMvp(
        conversationId,
        nextMessages.map(({ role, content }) => ({ role, content })),
      )
      const answer = response.choices?.[0]?.message?.content?.trim() || 'No response content.'
      setMessages([
        ...nextMessages,
        {
          id: messageId('assistant'),
          role: 'assistant',
          content: answer,
        },
      ])
    } catch (err) {
      setMessages([
        ...nextMessages,
        {
          id: messageId('error'),
          role: 'assistant',
          content: err instanceof Error ? err.message : 'Request failed.',
          status: 'error',
        },
      ])
    } finally {
      setIsSending(false)
    }
  }

  return {
    conversationId,
    draft,
    setDraft,
    messages,
    isSending,
    submit,
  }
}
