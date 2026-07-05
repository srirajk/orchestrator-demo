import type { AuthSession } from './auth'

export type Board = {
  panels: Panel[]
}

export type RangeKey = '24h' | '7d' | '30d'

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

export type CostSlice = {
  label: string
  costUsd: number
  tokens: number
  count: number
}

export type CostSummary = {
  range: RangeKey
  currency: 'USD'
  totalCostUsd: number
  totalTokens: number
  questions: number
  unitEconomics: {
    costPerQuestionUsd: number
    tokensPerQuestion: number
  }
  byModel: CostSlice[]
  byUser: CostSlice[]
  bySegment: CostSlice[]
}

export type ConversationTrace = {
  conversationId: string
  requestCount: number
  requests: Array<{
    requestId: string
    eventCount: number
    events: unknown[]
  }>
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

async function fetchInsights<T>(session: AuthSession, path: string): Promise<T> {
  const response = await fetch(path, {
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
    throw new Error(`Insights request did not complete: ${response.status} ${text}`)
  }

  return response.json() as Promise<T>
}

export async function fetchInsightsBoard(session: AuthSession, boardId: number, range: RangeKey = '24h'): Promise<Board> {
  return fetchInsights<Board>(session, `/v1/insights/boards/${boardId}?range=${encodeURIComponent(range)}`)
}

export async function fetchInsightsCost(session: AuthSession, range: RangeKey = '24h'): Promise<CostSummary> {
  return fetchInsights<CostSummary>(session, `/v1/insights/cost?range=${encodeURIComponent(range)}`)
}

export async function fetchConversationTrace(
  session: AuthSession,
  conversationId: string,
  limit = 20,
): Promise<ConversationTrace> {
  return fetchInsights<ConversationTrace>(
    session,
    `/v1/insights/conversations/${encodeURIComponent(conversationId)}/trace?limit=${limit}`,
  )
}

export async function verifyInsightsAccess(session: AuthSession): Promise<Board> {
  return fetchInsightsBoard(session, 1)
}
