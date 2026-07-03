/**
 * Generic fetch wrapper.
 * - Always includes credentials: 'include'
 * - On any 401 response: redirects to /api/auth/login
 */
export async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const resp = await fetch(path, {
    ...init,
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      ...(init?.headers ?? {}),
    },
  })

  if (resp.status === 401) {
    window.location.href = '/api/auth/login'
    // Return a never-resolving promise so callers don't see a value
    return new Promise(() => {})
  }

  if (!resp.ok) {
    const text = await resp.text().catch(() => resp.statusText)
    throw new Error(`API error ${resp.status}: ${text}`)
  }

  // 204 No Content (and any response with an empty body) must not be passed to resp.json()
  // or the browser throws "Unexpected end of JSON input". DELETE/archive PATCH return 204.
  if (resp.status === 204) return undefined as T
  const text = await resp.text()
  if (!text) return undefined as T
  return JSON.parse(text) as T
}

/**
 * For streaming SSE — returns the raw Response so the caller can
 * consume resp.body as a ReadableStream.
 * Still enforces credentials and handles 401.
 */
export async function apiStream(path: string, init?: RequestInit): Promise<Response> {
  const resp = await fetch(path, {
    ...init,
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      ...(init?.headers ?? {}),
    },
  })

  if (resp.status === 401) {
    window.location.href = '/api/auth/login'
    return new Promise(() => {})
  }

  if (!resp.ok) {
    const text = await resp.text().catch(() => resp.statusText)
    throw new Error(`API error ${resp.status}: ${text}`)
  }

  return resp
}
