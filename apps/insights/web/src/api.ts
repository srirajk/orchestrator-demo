import type { AuthSession } from './auth'

export class InsightsAccessDeniedError extends Error {
  constructor() {
    super('Insights access denied')
    this.name = 'InsightsAccessDeniedError'
  }
}

export class InsightsUnauthorizedError extends Error {
  constructor() {
    super('Insights session expired')
    this.name = 'InsightsUnauthorizedError'
  }
}

export async function verifyInsightsAccess(session: AuthSession): Promise<void> {
  const response = await fetch('/v1/insights/boards/1', {
    headers: {
      Authorization: `Bearer ${session.accessToken}`,
      Accept: 'application/json',
    },
  })

  if (response.status === 401) {
    throw new InsightsUnauthorizedError()
  }

  if (response.status === 403) {
    throw new InsightsAccessDeniedError()
  }

  if (!response.ok) {
    const text = await response.text().catch(() => response.statusText)
    throw new Error(`Insights access check failed: ${response.status} ${text}`)
  }
}
