import { ArrowRight, BarChart3, CircleAlert, LockKeyhole, LogOut, ShieldCheck, Sparkles } from 'lucide-react'
import type { ReactNode } from 'react'
import { useEffect, useMemo, useState } from 'react'
import { InsightsAccessDeniedError, InsightsUnauthorizedError, verifyInsightsAccess } from './api'
import { AuthSession, clearSession, completeCallback, getSession, startSignIn } from './auth'

type AppState =
  | { name: 'checking' }
  | { name: 'signed-out' }
  | { name: 'callback' }
  | { name: 'authenticated'; session: AuthSession }
  | { name: 'denied'; session: AuthSession }
  | { name: 'error'; message: string }

export default function App() {
  const [state, setState] = useState<AppState>(() => {
    if (window.location.pathname === '/callback') return { name: 'callback' }
    const session = getSession()
    return session ? { name: 'checking' } : { name: 'signed-out' }
  })

  useEffect(() => {
    let cancelled = false

    async function run() {
      try {
        if (window.location.pathname === '/callback') {
          const session = await completeCallback(window.location.search)
          window.history.replaceState({}, document.title, '/')
          await verifyInsightsAccess(session)
          if (!cancelled) setState({ name: 'authenticated', session })
          return
        }

        const session = getSession()
        if (!session) {
          if (!cancelled) setState({ name: 'signed-out' })
          return
        }

        await verifyInsightsAccess(session)
        if (!cancelled) setState({ name: 'authenticated', session })
      } catch (error) {
        if (cancelled) return

        const session = getSession()
        if (error instanceof InsightsAccessDeniedError && session) {
          setState({ name: 'denied', session })
          return
        }

        if (error instanceof InsightsUnauthorizedError) {
          clearSession()
          setState({ name: 'signed-out' })
          return
        }

        setState({ name: 'error', message: error instanceof Error ? error.message : 'Unable to sign in.' })
      }
    }

    run()
    return () => {
      cancelled = true
    }
  }, [])

  if (state.name === 'authenticated') return <InsightsShell session={state.session} />
  if (state.name === 'denied') return <NoAccessPage session={state.session} />
  if (state.name === 'error') return <ErrorPage message={state.message} />
  if (state.name === 'checking' || state.name === 'callback') return <LoadingPage />
  return <LandingPage />
}

function LandingPage() {
  return (
    <AuthFrame>
      <section className="auth-panel" aria-labelledby="insights-login-title">
        <BrandLockup />
        <div className="auth-copy">
          <p className="eyebrow">Meridian Private Banking</p>
          <h1 id="insights-login-title">Sign in to Insights</h1>
          <p>
            Continue through Axiom single sign-on to view authorized operational analytics for Conduit.
          </p>
        </div>
        <button type="button" className="primary-button" onClick={() => void startSignIn()}>
          Sign in with SSO
          <ArrowRight size={18} aria-hidden="true" />
        </button>
      </section>
    </AuthFrame>
  )
}

function LoadingPage() {
  return (
    <AuthFrame>
      <section className="auth-panel auth-panel-compact" aria-live="polite" aria-busy="true">
        <BrandLockup />
        <div className="loading-row">
          <span className="spinner" />
          <span>Checking Insights access</span>
        </div>
      </section>
    </AuthFrame>
  )
}

function NoAccessPage({ session }: { session: AuthSession }) {
  return (
    <AuthFrame>
      <section className="auth-panel" aria-labelledby="insights-denied-title">
        <BrandLockup />
        <div className="status-icon status-icon-warn">
          <LockKeyhole size={24} aria-hidden="true" />
        </div>
        <div className="auth-copy">
          <p className="eyebrow">Access not granted</p>
          <h1 id="insights-denied-title">You don't have Insights access</h1>
          <p>
            {session.profile.name} signed in successfully, but this workspace is limited to Conduit Insights
            administrators.
          </p>
        </div>
        <button type="button" className="secondary-button" onClick={signOutLocally}>
          <LogOut size={17} aria-hidden="true" />
          Sign out
        </button>
      </section>
    </AuthFrame>
  )
}

function ErrorPage({ message }: { message: string }) {
  return (
    <AuthFrame>
      <section className="auth-panel" aria-labelledby="insights-error-title">
        <BrandLockup />
        <div className="status-icon status-icon-danger">
          <CircleAlert size={24} aria-hidden="true" />
        </div>
        <div className="auth-copy">
          <p className="eyebrow">Sign-in interrupted</p>
          <h1 id="insights-error-title">Unable to open Insights</h1>
          <p>{message}</p>
        </div>
        <button type="button" className="primary-button" onClick={() => void startSignIn()}>
          Try SSO again
          <ArrowRight size={18} aria-hidden="true" />
        </button>
      </section>
    </AuthFrame>
  )
}

function InsightsShell({ session }: { session: AuthSession }) {
  const initials = useMemo(() => {
    return session.profile.name
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((part) => part[0]?.toUpperCase())
      .join('') || 'IA'
  }, [session.profile.name])

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <BrandLockup />
        <nav aria-label="Insights navigation">
          <a className="nav-item nav-item-active" href="/">
            <BarChart3 size={18} aria-hidden="true" />
            Overview
          </a>
        </nav>
      </aside>

      <main className="workspace">
        <header className="topbar">
          <div>
            <p className="eyebrow">Conduit Insights</p>
            <h1>Analytics workspace</h1>
          </div>
          <div className="user-menu" aria-label={`Signed in as ${session.profile.name}`}>
            <span className="avatar">{initials}</span>
            <span>{session.profile.name}</span>
            <button type="button" className="icon-button" onClick={signOutLocally} aria-label="Sign out">
              <LogOut size={17} aria-hidden="true" />
            </button>
          </div>
        </header>

        <section className="empty-shell" aria-labelledby="empty-shell-title">
          <div className="status-icon">
            <Sparkles size={24} aria-hidden="true" />
          </div>
          <p className="eyebrow">Authenticated</p>
          <h2 id="empty-shell-title">Insights shell ready</h2>
          <p>Boards will appear here as the analytics surfaces come online.</p>
        </section>
      </main>
    </div>
  )
}

function AuthFrame({ children }: { children: ReactNode }) {
  return (
    <main className="auth-frame">
      <div className="auth-background" />
      {children}
    </main>
  )
}

function BrandLockup() {
  return (
    <div className="brand-lockup">
      <div className="brand-mark">C</div>
      <div>
        <p>Conduit Insights</p>
        <span>Axiom secured analytics</span>
      </div>
      <ShieldCheck className="brand-shield" size={20} aria-hidden="true" />
    </div>
  )
}

function signOutLocally() {
  const session = getSession()
  clearSession()
  if (session?.idToken) {
    const logoutUrl = new URL('http://localhost:8084/connect/logout')
    logoutUrl.searchParams.set('id_token_hint', session.idToken)
    logoutUrl.searchParams.set('post_logout_redirect_uri', `${window.location.origin}/`)
    window.location.assign(logoutUrl.toString())
    return
  }
  window.location.assign('/')
}
