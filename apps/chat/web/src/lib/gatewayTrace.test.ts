import { describe, it, expect } from 'vitest'
import { selectStructuredInteraction } from './gatewayTrace'
import type { TraceEvent } from './gatewayTrace'

function evt(type: string, data: Record<string, unknown>): TraceEvent {
  return { type, requestId: 'r1', conversationId: 'c1', timestamp: Date.now(), data }
}

describe('selectStructuredInteraction', () => {
  it('returns null when there is no structured_interaction frame', () => {
    expect(selectStructuredInteraction([evt('request_start', { prompt: 'hi' })])).toBeNull()
  })

  it('returns the structured_interaction payload with options normalized to an array', () => {
    const si = selectStructuredInteraction([
      evt('request_start', { prompt: 'show holdings' }),
      evt('structured_interaction', {
        nonce: 'n1',
        question: 'Which one?',
        options: [{ value: 'ENT-1', label: 'Alpha' }],
      }),
    ])
    expect(si?.nonce).toBe('n1')
    expect(si?.question).toBe('Which one?')
    expect(si?.options).toHaveLength(1)
    expect(si?.options[0].value).toBe('ENT-1')
  })

  it('normalizes a missing options field to an empty array (pure free-text ask)', () => {
    const si = selectStructuredInteraction([evt('structured_interaction', { nonce: 'n1' })])
    expect(si?.options).toEqual([])
  })

  it('ignores a payload with no nonce (it cannot be resumed)', () => {
    expect(
      selectStructuredInteraction([evt('structured_interaction', { question: 'x' })]),
    ).toBeNull()
  })

  it('returns the LATEST structured_interaction for the turn', () => {
    const si = selectStructuredInteraction([
      evt('structured_interaction', { nonce: 'old', question: 'first' }),
      evt('structured_interaction', { nonce: 'new', question: 'second' }),
    ])
    expect(si?.nonce).toBe('new')
    expect(si?.question).toBe('second')
  })
})
