import React, { createContext, useContext, useState, useCallback, useEffect, useMemo } from 'react'
import type { User } from '../api/client'
import { AUTH_LOGOUT_EVENT, clearAdminToken, readAdminToken, writeAdminToken } from '../auth/tokenStorage'

interface AuthCtx {
  user: User | null
  token: string
  login: (token: string, user: User) => void
  logout: () => void
  isAdmin: boolean
}

const Ctx = createContext<AuthCtx | null>(null)

function decodeBase64Url(value: string): string {
  const normalized = value.replace(/-/g, '+').replace(/_/g, '/')
  const padded = normalized.padEnd(normalized.length + ((4 - normalized.length % 4) % 4), '=')
  const binary = atob(padded)
  const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0))
  return new TextDecoder().decode(bytes)
}

export function decodePayload(token: string): User | null {
  try {
    const part = token.split('.')[1]
    if (!part) return null
    const json = decodeBase64Url(part)
    const p = JSON.parse(json)
    const now = Math.floor(Date.now() / 1000)
    if (p.exp && p.exp < now) return null
    return {
      id: p.sub,
      username: p.username || p.name || p.sub,
      email: p.email || '',
      roles: p.roles || [],
      book: p.book || [],
      segments: p.segments || {},
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

  const syncFromStorage = useCallback(() => {
    const nextToken = readAdminToken()
    const nextUser = nextToken ? decodePayload(nextToken) : null
    if (nextToken && !nextUser) {
      clearAdminToken()
      setToken('')
      setUser(null)
      return
    }
    setToken(nextToken)
    setUser(nextUser)
  }, [])

  useEffect(() => {
    syncFromStorage()
    const onStorage = (event: StorageEvent) => {
      if (!event.key || event.key === 'meridian_admin_token' || event.key === 'conduit_admin_token') {
        syncFromStorage()
      }
    }
    const onLogout = () => {
      setToken('')
      setUser(null)
    }
    window.addEventListener('storage', onStorage)
    window.addEventListener(AUTH_LOGOUT_EVENT, onLogout)
    return () => {
      window.removeEventListener('storage', onStorage)
      window.removeEventListener(AUTH_LOGOUT_EVENT, onLogout)
    }
  }, [syncFromStorage])

  const isAdmin = useMemo(() => user?.roles.includes('platform_admin') ?? false, [user?.roles])
  const value = useMemo(
    () => ({ user, token, login, logout, isAdmin }),
    [user, token, login, logout, isAdmin],
  )

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}

export function useAuth() {
  const ctx = useContext(Ctx)
  if (!ctx) throw new Error('useAuth must be inside AuthProvider')
  return ctx
}
