import type { EventTone, TraceEvent } from './types'
import { formatDuration, getNumber, getString } from './format'

/**
 * Presentation-neutral helpers that turn a raw gateway trace event into a
 * tone + human title/detail. No domain strings are hardcoded — copy is derived
 * entirely from the event payload the gateway emits.
 */

export function eventTone(type: string): EventTone {
  if (type === 'gate') return 'indigo'
  if (type.includes('complete')) return 'green'
  if (type.includes('entitlement')) return 'indigo'
  if (type.includes('agent')) return 'blue'
  if (type.includes('synthesis')) return 'purple'
  if (type.includes('request')) return 'slate'
  if (type.includes('intent')) return 'yellow'
  return 'slate'
}

export function eventTitle(event: TraceEvent): string {
  const data = event.data || {}
  switch (event.type) {
    case 'request_start':
      return `Request from ${getString(data.userId, 'principal')}`
    case 'intent_classified':
      return `Intent ${getString(data.intent)}`
    case 'agents_resolved': {
      const selected = Array.isArray(data.selected) ? data.selected.length : 0
      const filtered = Array.isArray(data.filtered) ? data.filtered.length : 0
      return `${selected} agent${selected === 1 ? '' : 's'} selected, ${filtered} filtered`
    }
    case 'gate':
      return `${getString(data.gate, 'gate')} ${data.effect === 'deny' ? 'denied' : 'allowed'}`
    case 'entitlement_check':
      return `${data.allowed ? 'Access allowed' : 'Access denied'} for ${getString(data.relationshipId, 'resource')}`
    case 'agent_start':
      return `${getString(data.agentId, 'Agent')} started`
    case 'agent_complete':
      return `${getString(data.agentId, 'Agent')} ${getString(data.status, 'complete')}`
    case 'synthesis_start':
      return `Synthesis from ${getString(data.successCount, '0')} of ${getString(data.agentCount, '0')}`
    case 'request_complete':
      return `Complete in ${formatDuration(data.totalMs)}`
    default:
      return event.type.replace(/_/g, ' ')
  }
}

export function eventDetail(event: TraceEvent): string {
  const data = event.data || {}
  switch (event.type) {
    case 'request_start':
      return getString(data.prompt, '')
    case 'intent_classified': {
      const confidence = getNumber(data.confidence)
      return confidence === null ? getString(data.reasoning, '') : `${Math.round(confidence * 100)}% confidence`
    }
    case 'gate':
      return getString(data.reason, '')
    case 'entitlement_check':
      return getString(data.reason, getString(data.source, 'policy check'))
    case 'agent_complete':
      return `${formatDuration(data.durationMs)} - ${getString(data.dataPreview, '')}`
    case 'request_complete':
      return `${getString(data.successCount, '0')} successful / ${getString(data.agentCount, '0')} invoked`
    default:
      return ''
  }
}
