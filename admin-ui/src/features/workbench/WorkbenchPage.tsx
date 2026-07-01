import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Badge } from '../../components/ui/Badge'
import { useAuth } from '../../hooks/useAuth'
import { workbenchApi } from './api'
import { ChatPanel } from './components/ChatPanel'
import { ContextLedger } from './components/ContextLedger'
import { CoveragePanel } from './components/CoveragePanel'
import { HealthPanel } from './components/HealthPanel'
import { StatusPill } from './components/StatusPill'
import { TraceRail } from './components/TraceRail'
import { useTraceStream } from './hooks/useTraceStream'
import { useWorkbenchChat } from './hooks/useWorkbenchChat'

export function WorkbenchPage() {
  const { user } = useAuth()
  const chat = useWorkbenchChat()
  const trace = useTraceStream(chat.conversationId)

  const traceHealth = useQuery({
    queryKey: ['workbench', 'trace-health'],
    queryFn: workbenchApi.traceHealth,
    refetchInterval: 10000,
  })
  const domainsQuery = useQuery({
    queryKey: ['workbench', 'domains'],
    queryFn: workbenchApi.listDomains,
    retry: 1,
  })
  const agentsQuery = useQuery({
    queryKey: ['workbench', 'agents'],
    queryFn: workbenchApi.listAgents,
    retry: 1,
  })

  const domains = useMemo(
    () => Object.entries(domainsQuery.data || {}),
    [domainsQuery.data],
  )

  return (
    <div className="page-shell max-w-[1500px]">
      <div className="mb-5 flex flex-col gap-3 lg:flex-row lg:items-end lg:justify-between">
        <div>
          <p className="page-kicker mb-2">Gateway Operations</p>
          <h1 className="text-2xl font-semibold text-ink-900">Conduit Workbench</h1>
          <p className="muted-copy mt-1">Live control plane for chat, traces, coverage, and agent registry state.</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Badge color="slate">{user?.username || user?.id || 'principal'}</Badge>
          <StatusPill
            ok={traceHealth.data?.status === 'ok'}
            label={traceHealth.data?.status === 'ok' ? 'Gateway trace ok' : 'Gateway trace pending'}
          />
          <StatusPill ok={trace.connected} label={trace.connected ? 'SSE connected' : 'SSE offline'} />
        </div>
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-[minmax(0,1fr)_420px] gap-4">
        <div className="space-y-4 min-w-0">
          <ContextLedger
            userId={user?.id || ''}
            conversationId={chat.conversationId}
            events={trace.visibleEvents}
            traceSubscribers={traceHealth.data?.subscribers}
          />

          <ChatPanel
            messages={chat.messages}
            draft={chat.draft}
            isSending={chat.isSending}
            onDraftChange={chat.setDraft}
            onSubmit={chat.submit}
          />

          <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
            <CoveragePanel
              domains={domains}
              isLoading={domainsQuery.isLoading}
              error={domainsQuery.error instanceof Error ? domainsQuery.error : null}
              events={trace.visibleEvents}
            />
            <HealthPanel
              agents={agentsQuery.data || []}
              domains={domains}
              isLoadingAgents={agentsQuery.isLoading}
              agentsError={agentsQuery.error instanceof Error ? agentsQuery.error : null}
            />
          </div>
        </div>

        <TraceRail events={trace.visibleEvents} connected={trace.connected} error={trace.error} />
      </div>
    </div>
  )
}
