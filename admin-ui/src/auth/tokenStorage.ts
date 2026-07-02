const ADMIN_TOKEN_KEY = 'meridian_admin_token'
const LEGACY_ADMIN_TOKEN_KEY = 'conduit_admin_token'
export const AUTH_LOGOUT_EVENT = 'axiom-auth:logout'

export function readAdminToken(): string {
  return localStorage.getItem(ADMIN_TOKEN_KEY)
    || localStorage.getItem(LEGACY_ADMIN_TOKEN_KEY)
    || ''
}

export function writeAdminToken(token: string): void {
  localStorage.setItem(ADMIN_TOKEN_KEY, token)
  localStorage.removeItem(LEGACY_ADMIN_TOKEN_KEY)
}

export function clearAdminToken(): void {
  localStorage.removeItem(ADMIN_TOKEN_KEY)
  localStorage.removeItem(LEGACY_ADMIN_TOKEN_KEY)
}

export function notifyAuthLogout(): void {
  window.dispatchEvent(new CustomEvent(AUTH_LOGOUT_EVENT))
}
