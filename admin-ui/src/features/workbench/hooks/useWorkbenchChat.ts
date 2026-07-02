import { FormEvent, useCallback, useEffect, useRef, useState } from 'react'
import { workbenchApi } from '../api'
import type { WorkbenchMessage } from '../types'

function messageId(role: string): string {
  return `m-${Date.now()}-${role}-${Math.random().toString(36).slice(2, 7)}`
}

export function useWorkbenchChat(personaToken: string) {
  const abortRef = useRef<AbortController | null>(null)
  const mountedRef = useRef(true)
  const [conversationId, setConversationId] = useState(() => `workbench-${Date.now().toString(36)}`)
  const [draft, setDraft] = useState('')
  const [messages, setMessages] = useState<WorkbenchMessage[]>([])
  const [isSending, setIsSending] = useState(false)

  useEffect(() => () => {
    mountedRef.current = false
    abortRef.current?.abort()
  }, [])

  const resetConversation = useCallback(() => {
    abortRef.current?.abort()
    abortRef.current = null
    setConversationId(`workbench-${Date.now().toString(36)}`)
    setDraft('')
    setMessages([])
    setIsSending(false)
  }, [])

  async function submit(event: FormEvent) {
    event.preventDefault()
    const content = draft.trim()
    if (!content || isSending) return
    const abort = new AbortController()
    abortRef.current?.abort()
    abortRef.current = abort

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
      if (!personaToken) {
        throw new Error('Select a persona before sending a Workbench turn.')
      }
      const response = await workbenchApi.sendChatTurnMvp(
        conversationId,
        nextMessages.map(({ role, content }) => ({ role, content })),
        personaToken,
        abort.signal,
      )
      const answer = response.choices?.[0]?.message?.content?.trim() || 'No response content.'
      if (!mountedRef.current || abort.signal.aborted) return
      setMessages([
        ...nextMessages,
        {
          id: messageId('assistant'),
          role: 'assistant',
          content: answer,
        },
      ])
    } catch (err) {
      if (abort.signal.aborted) return
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
      if (mountedRef.current && abortRef.current === abort) {
        abortRef.current = null
        setIsSending(false)
      }
    }
  }

  return {
    conversationId,
    draft,
    setDraft,
    messages,
    isSending,
    submit,
    resetConversation,
  }
}
