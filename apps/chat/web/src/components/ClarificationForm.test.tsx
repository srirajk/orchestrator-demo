import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { ClarificationForm } from './ClarificationForm'
import type { StructuredInteraction } from '../lib/gatewayTrace'

function interaction(overrides: Partial<StructuredInteraction> = {}): StructuredInteraction {
  return {
    kind: 'clarify_entity',
    nonce: 'n1',
    question: 'Which one are you asking about?',
    options: [
      { value: 'ENT-1', label: 'Alpha', secondaryLabel: 'ENT-1' },
      { value: 'ENT-2', label: 'Beta', secondaryLabel: 'ENT-2' },
    ],
    freeText: { enabled: true, prompt: 'None of these — reply with it directly.', inputContract: 'data' },
    blocking: true,
    ...overrides,
  }
}

describe('ClarificationForm', () => {
  it('renders the question, every option, and the free-text escape from the event payload', () => {
    render(<ClarificationForm interaction={interaction()} onSubmit={() => {}} />)

    expect(screen.getByText('Which one are you asking about?')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Alpha/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Beta/ })).toBeInTheDocument()
    expect(
      screen.getByPlaceholderText('None of these — reply with it directly.'),
    ).toBeInTheDocument()
  })

  it('submits the chosen option value when an option is clicked', () => {
    const onSubmit = vi.fn()
    render(<ClarificationForm interaction={interaction()} onSubmit={onSubmit} />)

    fireEvent.click(screen.getByRole('button', { name: /Beta/ }))
    expect(onSubmit).toHaveBeenCalledWith({ selection: 'ENT-2' })
  })

  it('submits free text via the escape hatch', () => {
    const onSubmit = vi.fn()
    render(<ClarificationForm interaction={interaction()} onSubmit={onSubmit} />)

    fireEvent.change(screen.getByPlaceholderText(/None of these/), {
      target: { value: 'the Geneva mandate' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))
    expect(onSubmit).toHaveBeenCalledWith({ freeText: 'the Geneva mandate' })
  })

  it('never renders a hidden-candidate count (no enumeration oracle)', () => {
    render(<ClarificationForm interaction={interaction()} onSubmit={() => {}} />)
    // The rendered text is exactly the two option labels + the question + escape — no "N more" leak.
    expect(screen.queryByText(/\bmore\b/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/hidden/i)).not.toBeInTheDocument()
  })

  it('gracefully renders a pure free-text ask (no options) — the text still shows', () => {
    const onSubmit = vi.fn()
    render(
      <ClarificationForm
        interaction={interaction({ options: [] })}
        onSubmit={onSubmit}
      />,
    )
    expect(screen.getByText('Which one are you asking about?')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Alpha/ })).not.toBeInTheDocument()
    // The escape is still usable so the user is never trapped.
    fireEvent.change(screen.getByPlaceholderText(/None of these/), { target: { value: 'x' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))
    expect(onSubmit).toHaveBeenCalledWith({ freeText: 'x' })
  })

  it('disables interaction while a resume is in flight', () => {
    const onSubmit = vi.fn()
    render(<ClarificationForm interaction={interaction()} disabled onSubmit={onSubmit} />)
    fireEvent.click(screen.getByRole('button', { name: /Alpha/ }))
    expect(onSubmit).not.toHaveBeenCalled()
  })
})
