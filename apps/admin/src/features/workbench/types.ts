export type ChatRole = 'system' | 'user' | 'assistant'

export interface ChatMessage {
  role: ChatRole
  content: string
}

export interface WorkbenchMessage extends ChatMessage {
  id: string
  status?: 'error'
}

export interface TraceAgentRef {
  agentId?: string
  protocol?: string
  score?: number
}

export interface TraceFilteredRef {
  agentId?: string
  reason?: string
}

export interface TraceEventData extends Record<string, unknown> {
  userId?: string
  prompt?: string
  intent?: string
  confidence?: number
  reasoning?: string
  selected?: TraceAgentRef[]
  filtered?: TraceFilteredRef[]
  relationshipId?: string
  allowed?: boolean
  reason?: string
  source?: string
  agentId?: string
  protocol?: string
  durationMs?: number
  status?: string
  dataPreview?: string
  agentCount?: number
  successCount?: number
  totalMs?: number
}

export interface TraceEvent {
  type: string
  requestId: string
  conversationId?: string | null
  timestamp: number
  data?: TraceEventData
}

export interface TraceHealth {
  status: string
  subscribers: number
}

export interface DomainManifest {
  domain_id?: string
  domainId?: string
  display_name?: string
  displayName?: string
  coverage?: {
    discover_url?: string
    discoverUrl?: string
    check_url?: string
    checkUrl?: string
    resolve_url?: string
    resolveUrl?: string
    cache_ttl_seconds?: number
    cacheTtlSeconds?: number
  }
}

export type DomainEntry = [string, DomainManifest]

export interface AgentManifest {
  agent_id?: string
  agentId?: string
  name?: string
  description?: string
  version?: string
  domain?: string
  sub_domain?: string
  subDomain?: string
  protocol?: string
  indexed?: boolean
  registered_at?: string
  registeredAt?: string
  constraints?: {
    sla_timeout_ms?: number
    slaTimeoutMs?: number
    data_classification?: string
    dataClassification?: string
    is_mutating?: boolean
    isMutating?: boolean
  }
  connection?: {
    openapi_url?: string
    openapiUrl?: string
    operation_id?: string
    operationId?: string
    server_url?: string
    serverUrl?: string
    tool?: string
  }
  resolved_connection?: {
    base_url?: string
    baseUrl?: string
    method?: string
    path?: string
  }
}

export interface ChatCompletionResponse {
  choices?: Array<{
    message?: {
      role?: string
      content?: string
    }
    finish_reason?: string
  }>
}

export type StatusTone = 'green' | 'red' | 'slate'
export type EventTone = 'blue' | 'green' | 'yellow' | 'red' | 'slate' | 'purple' | 'indigo'
