const CLIENT_ID = 'conduit-insights'
const AUTHORIZATION_ENDPOINT = 'http://localhost:8084/oauth/authorize'
const TOKEN_ENDPOINT = '/oauth/token'
const REDIRECT_URI = `${window.location.origin}/callback`
const SCOPE = 'openid profile email offline_access'

const SESSION_KEY = 'conduit-insights.session'
const PKCE_KEY = 'conduit-insights.pkce'

export type AuthSession = {
  accessToken: string
  idToken?: string
  refreshToken?: string
  expiresAt: number
  profile: UserProfile
}

export type UserProfile = {
  subject: string
  name: string
  email?: string
  roles: string[]
}

type PkceRequest = {
  state: string
  nonce: string
  verifier: string
}

type TokenResponse = {
  access_token: string
  id_token?: string
  refresh_token?: string
  expires_in?: number
}

export function getSession(): AuthSession | null {
  const raw = sessionStorage.getItem(SESSION_KEY)
  if (!raw) return null

  try {
    const session = JSON.parse(raw) as AuthSession
    if (!session.accessToken || session.expiresAt <= Date.now() + 30_000) {
      clearSession()
      return null
    }
    return session
  } catch {
    clearSession()
    return null
  }
}

export function clearSession() {
  sessionStorage.removeItem(SESSION_KEY)
  sessionStorage.removeItem(PKCE_KEY)
}

export async function startSignIn(): Promise<never> {
  const state = randomUrlSafe(32)
  const nonce = randomUrlSafe(32)
  const verifier = randomUrlSafe(64)
  const challenge = await sha256Base64Url(verifier)

  sessionStorage.setItem(PKCE_KEY, JSON.stringify({ state, nonce, verifier } satisfies PkceRequest))

  const url = new URL(AUTHORIZATION_ENDPOINT)
  url.searchParams.set('response_type', 'code')
  url.searchParams.set('client_id', CLIENT_ID)
  url.searchParams.set('redirect_uri', REDIRECT_URI)
  url.searchParams.set('scope', SCOPE)
  url.searchParams.set('state', state)
  url.searchParams.set('nonce', nonce)
  url.searchParams.set('code_challenge', challenge)
  url.searchParams.set('code_challenge_method', 'S256')

  window.location.assign(url.toString())
  return new Promise<never>(() => {}) as never
}

export async function completeCallback(search: string): Promise<AuthSession> {
  const params = new URLSearchParams(search)
  const error = params.get('error')
  if (error) {
    throw new Error(params.get('error_description') || 'Axiom sign-in did not complete.')
  }

  const code = params.get('code')
  const state = params.get('state')
  const pkce = readPkce()
  if (!code || !state || !pkce || pkce.state !== state) {
    throw new Error('Axiom could not verify this sign-in response. Please try again.')
  }

  const body = new URLSearchParams({
    grant_type: 'authorization_code',
    client_id: CLIENT_ID,
    redirect_uri: REDIRECT_URI,
    code,
    code_verifier: pkce.verifier,
  })

  const response = await fetch(TOKEN_ENDPOINT, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    body,
  })

  if (!response.ok) {
    const text = await response.text().catch(() => response.statusText)
    throw new Error(`Axiom token exchange did not complete: ${response.status} ${text}`)
  }

  const token = (await response.json()) as TokenResponse
  if (!token.access_token) {
    throw new Error('Axiom did not return an access token for this session.')
  }

  const claims = decodeJwt(token.id_token || token.access_token)
  if (claims.nonce && claims.nonce !== pkce.nonce) {
    throw new Error('Axiom could not verify this sign-in nonce. Please try again.')
  }

  const session: AuthSession = {
    accessToken: token.access_token,
    idToken: token.id_token,
    refreshToken: token.refresh_token,
    expiresAt: Date.now() + (token.expires_in ?? 7200) * 1000,
    profile: {
      subject: stringClaim(claims.sub, 'user'),
      name: stringClaim(claims.name, stringClaim(claims.preferred_username, stringClaim(claims.sub, 'User'))),
      email: typeof claims.email === 'string' ? claims.email : undefined,
      roles: Array.isArray(claims.roles) ? claims.roles.filter((role): role is string => typeof role === 'string') : [],
    },
  }

  sessionStorage.setItem(SESSION_KEY, JSON.stringify(session))
  sessionStorage.removeItem(PKCE_KEY)
  return session
}

function readPkce(): PkceRequest | null {
  const raw = sessionStorage.getItem(PKCE_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as PkceRequest
  } catch {
    sessionStorage.removeItem(PKCE_KEY)
    return null
  }
}

function stringClaim(value: unknown, fallback: string): string {
  return typeof value === 'string' && value.trim() ? value : fallback
}

function decodeJwt(token: string): Record<string, unknown> {
  const part = token.split('.')[1]
  if (!part) return {}
  const normalized = part.replace(/-/g, '+').replace(/_/g, '/')
  const padded = normalized.padEnd(normalized.length + ((4 - (normalized.length % 4)) % 4), '=')
  const json = atob(padded)
  return JSON.parse(decodeURIComponent(Array.from(json, (char) => {
    return `%${char.charCodeAt(0).toString(16).padStart(2, '0')}`
  }).join(''))) as Record<string, unknown>
}

function randomUrlSafe(bytes: number): string {
  const data = new Uint8Array(bytes)
  crypto.getRandomValues(data)
  return base64Url(data)
}

async function sha256Base64Url(input: string): Promise<string> {
  const data = new TextEncoder().encode(input)
  const digest = await crypto.subtle.digest('SHA-256', data)
  return base64Url(new Uint8Array(digest))
}

function base64Url(data: Uint8Array): string {
  let text = ''
  data.forEach((byte) => {
    text += String.fromCharCode(byte)
  })
  return btoa(text).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}
