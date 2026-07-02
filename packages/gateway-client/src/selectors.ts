import type { AgentManifest, DomainManifest, TraceEvent } from './types'

/**
 * Manifest field selectors — the gateway may emit snake_case or camelCase
 * depending on serializer config, so every accessor tolerates both. No domain
 * knowledge lives here; these are generic manifest-shape readers.
 */

export function domainId(domain: DomainManifest, fallback: string): string {
  return domain.domain_id || domain.domainId || fallback
}

export function domainName(domain: DomainManifest, fallback: string): string {
  return domain.display_name || domain.displayName || domainId(domain, fallback)
}

export function coverageConfigured(domain: DomainManifest): boolean {
  const coverage = domain.coverage
  return Boolean(coverage?.discover_url || coverage?.discoverUrl)
    && Boolean(coverage?.check_url || coverage?.checkUrl)
    && Boolean(coverage?.resolve_url || coverage?.resolveUrl)
}

export function agentId(agent: AgentManifest): string {
  return agent.agent_id || agent.agentId || agent.name || 'agent'
}

export function subDomain(agent: AgentManifest): string {
  return agent.sub_domain || agent.subDomain || 'general'
}

export function timeoutMs(agent: AgentManifest): number | undefined {
  return agent.constraints?.sla_timeout_ms || agent.constraints?.slaTimeoutMs
}

export function classification(agent: AgentManifest): string {
  return agent.constraints?.data_classification || agent.constraints?.dataClassification || 'internal'
}

export function latestOf(events: TraceEvent[], type: string): TraceEvent | undefined {
  return [...events].reverse().find((event) => event.type === type)
}
