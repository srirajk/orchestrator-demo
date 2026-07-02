import { AlertTriangle, CheckCircle2, Server, XCircle } from 'lucide-react'
import { Badge } from '../../../components/ui/Badge'
import { Skeleton } from '../../../components/ui/Skeleton'
import type { AgentManifest, DomainEntry } from '../types'
import { agentId, classification, subDomain, timeoutMs } from '../utils/selectors'
import { Panel } from './Panel'

interface HealthPanelProps {
  agents: AgentManifest[]
  domains: DomainEntry[]
  isLoadingAgents: boolean
  agentsError: Error | null
  isLoadingDomains?: boolean
  domainsError?: Error | null
}

export function HealthPanel({
  agents,
  domains,
  isLoadingAgents,
  agentsError,
  isLoadingDomains = false,
  domainsError = null,
}: HealthPanelProps) {
  const indexed = agents.filter((agent) => agent.indexed).length
  const protocols = new Set(agents.map((agent) => agent.protocol).filter(Boolean))

  return (
    <Panel title="Domain and Agent Health" icon={Server}>
      <div className="p-4">
        <div className="grid grid-cols-3 gap-3 mb-4">
          <Metric
            label="Domains"
            value={domains.length}
            state={isLoadingDomains ? 'Loading' : domainsError ? 'Unavailable' : undefined}
          />
          <Metric label="Agents" value={agents.length} />
          <Metric label="Protocols" value={protocols.size} />
        </div>

        {domainsError && (
          <div className="mb-3 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800 flex items-center gap-2">
            <AlertTriangle size={15} />
            Domain registry unavailable
          </div>
        )}

        {agentsError && (
          <div className="mb-3 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700 flex items-center gap-2">
            <AlertTriangle size={15} />
            Registry unavailable
          </div>
        )}

        {isLoadingAgents ? (
          <div className="space-y-2">
            {[...Array(4)].map((_, index) => (
              <Skeleton key={index} className="h-16 w-full" />
            ))}
          </div>
        ) : agents.length > 0 ? (
          <div className="divide-y divide-slate-100 rounded-lg border border-slate-200 overflow-hidden">
            {agents.map((agent) => (
              <div key={agentId(agent)} className="px-3 py-3 bg-white hover:bg-slate-50">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <p className="text-sm font-medium text-slate-800 truncate">{agent.name || agentId(agent)}</p>
                    <p className="text-xs text-slate-500 truncate">
                      {agentId(agent)} / {subDomain(agent)}
                    </p>
                    <div className="flex flex-wrap gap-1.5 mt-2">
                      <Badge color="blue">{agent.protocol || 'protocol'}</Badge>
                      <Badge color="slate">{classification(agent)}</Badge>
                      {timeoutMs(agent) && <Badge color="yellow">{timeoutMs(agent)} ms</Badge>}
                    </div>
                  </div>
                  <div className="text-right shrink-0">
                    {agent.indexed ? (
                      <CheckCircle2 size={18} className="text-green-600 ml-auto" />
                    ) : (
                      <XCircle size={18} className="text-slate-400 ml-auto" />
                    )}
                    <p className="text-[11px] text-slate-500 mt-1">{agent.indexed ? 'Indexed' : 'Registered'}</p>
                  </div>
                </div>
              </div>
            ))}
          </div>
        ) : (
          <p className="text-sm text-slate-500">No registered agents.</p>
        )}

        <p className="text-xs text-slate-500 mt-3">
          Per-agent liveness probes are not exposed by the gateway API yet.
          Registry and indexing status are real.
          {indexed ? ` ${indexed} indexed.` : ''}
        </p>
      </div>
    </Panel>
  )
}

function Metric({ label, value, state }: { label: string; value: number; state?: string }) {
  return (
    <div className="rounded-lg border border-slate-200 px-3 py-2 bg-slate-50">
      <p className="text-[11px] uppercase tracking-normal font-medium text-slate-400">{label}</p>
      <p className="text-lg font-bold text-slate-900 mt-0.5">{state || value}</p>
    </div>
  )
}
