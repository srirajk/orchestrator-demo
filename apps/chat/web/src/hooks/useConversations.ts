import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '../api/client'
import type { Conversation, ConversationDetail } from '../api/types'

export function useConversations() {
  return useQuery<Conversation[]>({
    queryKey: ['conversations'],
    queryFn: () => apiFetch<Conversation[]>('/api/conversations'),
    staleTime: 30_000,
  })
}

export function useConversationDetail(id: string | undefined) {
  return useQuery<ConversationDetail>({
    queryKey: ['conversation', id],
    queryFn: () => apiFetch<ConversationDetail>(`/api/conversations/${id}`),
    enabled: !!id && id !== 'new',
    staleTime: 0,
  })
}

export function useCreateConversation() {
  const qc = useQueryClient()
  return useMutation<Conversation, Error, { title?: string }>({
    mutationFn: (body) =>
      apiFetch<Conversation>('/api/conversations', {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['conversations'] })
    },
  })
}

export function useUpdateConversation() {
  const qc = useQueryClient()
  return useMutation<Conversation, Error, { id: string; title?: string; archived?: boolean }>({
    mutationFn: ({ id, ...body }) =>
      apiFetch<Conversation>(`/api/conversations/${id}`, {
        method: 'PATCH',
        body: JSON.stringify(body),
      }),
    onSuccess: (_data, vars) => {
      void qc.invalidateQueries({ queryKey: ['conversations'] })
      void qc.invalidateQueries({ queryKey: ['conversation', vars.id] })
    },
  })
}

export function useDeleteConversation() {
  const qc = useQueryClient()
  return useMutation<void, Error, string>({
    mutationFn: (id) =>
      apiFetch<void>(`/api/conversations/${id}`, { method: 'DELETE' }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['conversations'] })
    },
  })
}
