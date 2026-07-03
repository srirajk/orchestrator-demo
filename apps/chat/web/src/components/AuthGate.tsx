import React, { createContext, useContext, useEffect } from 'react'
import { useAuth } from '../hooks/useAuth'
import { redirectToLogin } from '../api/client'
import type { User } from '../api/types'

const UserContext = createContext<User | null>(null)

export function useUser(): User {
  const u = useContext(UserContext)
  if (!u) throw new Error('useUser must be used inside AuthGate')
  return u
}

interface Props {
  children: React.ReactNode
}

export function AuthGate({ children }: Props) {
  const { user, isLoading, error } = useAuth()

  // Redirect to login in an effect rather than during render to avoid double-redirect /
  // render-phase side-effects and potential redirect loops with apiFetch's own 401 handling.
  useEffect(() => {
    if (!isLoading && (error || !user)) {
      redirectToLogin()
    }
  }, [isLoading, error, user])

  if (isLoading) {
    return (
      <div className="flex h-screen items-center justify-center bg-axiom-900">
        <div className="flex flex-col items-center gap-4">
          {/* Spinner ring */}
          <div className="h-10 w-10 rounded-full border-4 border-axiom-700 border-t-gold-400 animate-spin" />
          <p className="text-sm text-axiom-300 font-medium">Loading…</p>
        </div>
      </div>
    )
  }

  if (error || !user) {
    // Effect above will redirect; show a neutral state while that happens
    return (
      <div className="flex h-screen items-center justify-center bg-axiom-900">
        <p className="text-sm text-axiom-300">Redirecting to login…</p>
      </div>
    )
  }

  return <UserContext.Provider value={user}>{children}</UserContext.Provider>
}
