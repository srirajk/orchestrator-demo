import React, { createContext, useContext } from 'react'
import { useAuth } from '../hooks/useAuth'
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
    window.location.href = '/api/auth/login'
    return null
  }

  return <UserContext.Provider value={user}>{children}</UserContext.Provider>
}
