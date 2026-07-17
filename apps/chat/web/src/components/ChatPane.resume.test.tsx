import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { TraceEvent } from '../lib/gatewayTrace'

// ── Mocks: isolate ChatPane from the network + data hooks so the resume flow is deterministic. ──

const apiStreamMock = vi.fn()
vi.mock('../api/client', () => ({
  apiStream: (...args: unknown[]) => apiStreamMock(...args),
  redirectToLogin: () => undefined,
}))

const structuredEvent: TraceEvent = {
  type: 'structured_interaction',
  requestId: 'r1',
  conversationId: 'conv-1',
  timestamp: Date.now(),
  data: {
    nonce: 'nonce-xyz',
    question: 'Which client relationship?',
    options: [{ value: 'REL-00042', label: 'Whitman Family Office', secondaryLabel: 'REL-00042' }],
    freeText: { enabled: true, prompt: 'None of these — reply with it directly.', inputContract: 'data' },
    blocking: true,
  },
}

vi.mock('../hooks/useTraceStream', async (importActual) => {
  const actual = await importActual<typeof import('../hooks/useTraceStream')>()
  return {
    ...actual,
    useTraceStream: () => ({ events: [structuredEvent], status: 'open' as const, clear: () => {} }),
  }
})

vi.mock('../hooks/useConversations', () => ({
  useConversationDetail: () => ({
    data: { conversation: { id: 'conv-1', title: 'Chat' }, messages: [] },
    isLoading: false,
  }),
  useCreateConversation: () => ({ mutateAsync: vi.fn() }),
}))

import { ChatPane } from './ChatPane'

/** A ReadableStream of an OpenAI-shaped SSE completion carrying `text`. */
function sseStream(text: string): ReadableStream<Uint8Array> {
  const enc = new TextEncoder()
  const frames = [
    'data: {"choices":[{"delta":{"role":"assistant"}}]}\n\n',
    `data: {"choices":[{"delta":{"content":${JSON.stringify(text)}}}]}\n\n`,
    'data: {"choices":[{"delta":{},"finish_reason":"stop"}]}\n\n',
    'data: [DONE]\n\n',
  ]
  return new ReadableStream({
    start(controller) {
      for (const f of frames) controller.enqueue(enc.encode(f))
      controller.close()
    },
  })
}

function renderPane() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/c/conv-1']}>
        <Routes>
          <Route path="/c/:id" element={<ChatPane />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('ChatPane structured-clarification resume', () => {
  beforeEach(() => {
    apiStreamMock.mockReset()
  })

  it('renders the form from the OOB event, submits the right payload, and streams the resumed answer', async () => {
    apiStreamMock.mockResolvedValue({ body: sseStream('Resumed answer for Whitman.') } as Response)

    renderPane()

    // The form rendered from the structured_interaction event.
    const option = await screen.findByRole('button', { name: /Whitman Family Office/ })
    fireEvent.click(option)

    // POSTs to the resume endpoint with the descriptor nonce + chosen selection.
    await waitFor(() => expect(apiStreamMock).toHaveBeenCalled())
    const [path, init] = apiStreamMock.mock.calls[0]
    expect(path).toBe('/api/clarify/resolve')
    expect(JSON.parse((init as RequestInit).body as string)).toEqual({
      conversationId: 'conv-1',
      nonce: 'nonce-xyz',
      selection: 'REL-00042',
    })

    // The resumed answer streams back and renders (reusing the normal streaming render).
    expect(await screen.findByText('Resumed answer for Whitman.')).toBeInTheDocument()
  })

  it('submits a free-text escape answer as freeText (no selection)', async () => {
    apiStreamMock.mockResolvedValue({ body: sseStream('ok') } as Response)

    renderPane()

    const input = await screen.findByPlaceholderText(/None of these/)
    fireEvent.change(input, { target: { value: 'the Geneva mandate' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))

    await waitFor(() => expect(apiStreamMock).toHaveBeenCalled())
    const [, init] = apiStreamMock.mock.calls[0]
    expect(JSON.parse((init as RequestInit).body as string)).toEqual({
      conversationId: 'conv-1',
      nonce: 'nonce-xyz',
      freeText: 'the Geneva mandate',
    })
  })
})
