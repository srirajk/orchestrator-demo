import { useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { Badge } from '../../components/ui/Badge'
import { Select } from '../../components/ui/Input'
import { authApi, usersApi, type User as AdminUser } from '../../api/client'
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
  const { user, token, isAdmin } = useAuth()
  const [personaId, setPersonaId] = useState('')
  const [personaToken, setPersonaToken] = useState('')
  const [personaUser, setPersonaUser] = useState<AdminUser | null>(null)

  const personasQuery = useQuery({
    queryKey: ['workbench', 'personas'],
    queryFn: usersApi.list,
    enabled: isAdmin,
  })
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
  const personas = useMemo(
    () => (personasQuery.data || []).filter((candidate) => candidate.isActive !== false),
    [personasQuery.data],
  )

  const impersonate = useMutation({
    mutationFn: authApi.impersonate,
    onSuccess: (res) => {
      setPersonaToken(res.accessToken)
      setPersonaUser(res.user)
    },
    onError: () => {
      setPersonaToken('')
      setPersonaUser(null)
    },
  })

  useEffect(() => {
    if (isAdmin) return
    setPersonaId(user?.id || '')
    setPersonaUser(user)
    setPersonaToken(token)
  }, [isAdmin, token, user])

  useEffect(() => {
    if (!isAdmin || personaId || personas.length === 0) return
    const defaultPersona =
      personas.find((candidate) => !candidate.roles.includes('platform_admin') && candidate.segments.length > 0)
      || personas.find((candidate) => candidate.id !== user?.id)
      || personas[0]
    setPersonaId(defaultPersona.id)
  }, [isAdmin, personaId, personas, user?.id])

  useEffect(() => {
    if (!isAdmin || !personaId) return
    impersonate.mutate(personaId)
  }, [isAdmin, personaId])

  const chat = useWorkbenchChat(personaToken)
  const trace = useTraceStream(chat.conversationId, personaToken)

  useEffect(() => {
    chat.resetConversation()
  }, [personaId, chat.resetConversation])

  return (
    <div className="page-shell max-w-[1500px]">
      <div className="mb-5 flex flex-col gap-3 lg:flex-row lg:items-end lg:justify-between">
        <div>
          <p className="page-kicker mb-2">Gateway Operations</p>
          <h1 className="text-2xl font-semibold text-ink-900">Conduit Workbench</h1>
          <p className="muted-copy mt-1">Live control plane for chat, traces, coverage, and agent registry state.</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {isAdmin ? (
            <div className="min-w-[220px]">
              <Select
                id="workbench-persona"
                aria-label="Workbench persona"
                value={personaId}
                onChange={(event) => setPersonaId(event.target.value)}
                disabled={personasQuery.isLoading || impersonate.isPending}
                className="py-1.5 text-xs"
              >
                <option value="">Select persona</option>
                {personas.map((candidate) => (
                  <option key={candidate.id} value={candidate.id}>
                    {candidate.username || candidate.id}
                  </option>
                ))}
              </Select>
            </div>
          ) : null}
          <Badge color={personaToken ? 'green' : 'yellow'}>
            {personaUser?.username || personaId || 'persona pending'}
          </Badge>
          <StatusPill
            ok={traceHealth.data?.status === 'ok'}
            label={traceHealth.data?.status === 'ok' ? 'Gateway trace ok' : 'Gateway trace pending'}
          />
          <StatusPill
            ok={!!personaToken}
            label={personaToken ? 'Persona JWT ready' : 'Persona token pending'}
          />
          <StatusPill ok={trace.connected} label={trace.connected ? 'SSE connected' : 'SSE offline'} />
        </div>
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-[minmax(0,1fr)_420px] gap-4">
        <div className="space-y-4 min-w-0">
          <ContextLedger
            userId={personaUser?.id || personaId || ''}
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
            onReset={chat.resetConversation}
            disabled={!personaToken || impersonate.isPending}
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
              isLoadingDomains={domainsQuery.isLoading}
              domainsError={domainsQuery.error instanceof Error ? domainsQuery.error : null}
            />
          </div>
        </div>

        <TraceRail events={trace.visibleEvents} connected={trace.connected} error={trace.error} />
      </div>
    </div>
  )
}
