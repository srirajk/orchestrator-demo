import { useQuery } from '@tanstack/react-query'
import { apiFetch } from '../api/client'
import type { User } from '../api/types'

export function useAuth() {
  const { data: user, isLoading, error } = useQuery<User>({
    queryKey: ['me'],
    queryFn: () => apiFetch<User>('/api/me'),
    retry: false,
    staleTime: 5 * 60 * 1000,
  })

  return { user, isLoading, error }
}
