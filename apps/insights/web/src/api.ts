import type { AuthSession } from './auth'

export type Board = {
  panels: Panel[]
}

export type Panel = {
  id: string
  title: string
  type: 'stat' | 'area' | 'line' | 'donut' | 'bars' | 'table' | 'waterfall'
  unit: string
  status: 'ok' | 'unavailable'
  value?: number | string
  delta?: number
  series?: Point[]
  rows?: Array<LabeledValue | Record<string, unknown>>
}

export type Point = {
  t: number
  v: number
}

export type LabeledValue = {
  label: string
  value: number
}

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

export async function fetchInsightsBoard(session: AuthSession, boardId: number): Promise<Board> {
  const response = await fetch(`/v1/insights/boards/${boardId}`, {
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
    throw new Error(`Insights board did not load: ${response.status} ${text}`)
  }

  return response.json() as Promise<Board>
}

export async function verifyInsightsAccess(session: AuthSession): Promise<Board> {
  return fetchInsightsBoard(session, 1)
}
