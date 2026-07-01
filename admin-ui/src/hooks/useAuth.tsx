import React, { createContext, useContext, useState, useCallback } from 'react'
import type { User } from '../api/client'
import { clearAdminToken, readAdminToken, writeAdminToken } from '../auth/tokenStorage'

interface AuthCtx {
  user: User | null
  token: string
  login: (token: string, user: User) => void
  logout: () => void
  isAdmin: boolean
}

const Ctx = createContext<AuthCtx | null>(null)

function decodePayload(token: string): User | null {
  try {
    const part = token.split('.')[1]
    const json = atob(part.replace(/-/g, '+').replace(/_/g, '/'))
    const p = JSON.parse(json)
    const now = Math.floor(Date.now() / 1000)
    if (p.exp && p.exp < now) return null
    return {
      id: p.sub,
      username: p.username || p.name || p.sub,
      email: p.email || '',
      roles: p.roles || [],
      book: p.book || [],
      segments: p.segments || [],
      clearance: p.clearance || 1,
      classification: p.classification || 'public',
      team: p.team || '',
      adminDomains: p.admin_domains || p.adminDomains || [],
    }
  } catch {
    return null
  }
}

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [token, setToken] = useState(() => readAdminToken())
  const [user, setUser] = useState<User | null>(() => {
    const t = readAdminToken()
    return t ? decodePayload(t) : null
  })

  const login = useCallback((t: string, u: User) => {
    writeAdminToken(t)
    setToken(t)
    setUser(u)
  }, [])

  const logout = useCallback(() => {
    clearAdminToken()
    setToken('')
    setUser(null)
  }, [])

  const isAdmin = user?.roles.includes('platform_admin') ?? false

  return <Ctx.Provider value={{ user, token, login, logout, isAdmin }}>{children}</Ctx.Provider>
}

export function useAuth() {
  const ctx = useContext(Ctx)
  if (!ctx) throw new Error('useAuth must be inside AuthProvider')
  return ctx
}
