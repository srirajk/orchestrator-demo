import { ShieldCheck } from 'lucide-react'
import { Badge } from '../../../components/ui/Badge'
import { Skeleton } from '../../../components/ui/Skeleton'
import type { DomainEntry, TraceEvent } from '../types'
import { getString } from '../utils/format'
import { coverageConfigured, domainId, domainName, latestOf } from '../utils/selectors'
import { Panel } from './Panel'
import { StatusPill } from './StatusPill'

interface CoveragePanelProps {
  domains: DomainEntry[]
  isLoading: boolean
  error: Error | null
  events: TraceEvent[]
}

export function CoveragePanel({ domains, isLoading, error, events }: CoveragePanelProps) {
  const entitlement = latestOf(events, 'entitlement_check')

  return (
    <Panel title="Access and Coverage" icon={ShieldCheck}>
      <div className="p-4 space-y-4">
        <div className="rounded-lg border border-slate-200 overflow-hidden">
          <div className="px-3 py-2 bg-slate-50 border-b border-slate-200 flex items-center justify-between">
            <span className="text-xs font-semibold text-slate-600">Last access check</span>
            {entitlement ? (
              <Badge color={entitlement.data?.allowed ? 'green' : 'red'}>
                {entitlement.data?.allowed ? 'Allowed' : 'Denied'}
              </Badge>
            ) : (
              <Badge>Pending</Badge>
            )}
          </div>
          <div className="px-3 py-3">
            <p className="text-sm font-medium text-slate-800">
              {getString(entitlement?.data?.relationshipId, 'No resolved resource')}
            </p>
            <p className="text-xs text-slate-500 mt-1">
              {getString(entitlement?.data?.source, 'Coverage and entitlement events appear after routing.')}
            </p>
          </div>
        </div>

        <div>
          <div className="flex items-center justify-between mb-2">
            <p className="text-xs font-semibold text-slate-600">Coverage services</p>
            {error && <Badge color="red">Unavailable</Badge>}
          </div>
          {isLoading ? (
            <div className="space-y-2">
              <Skeleton className="h-14 w-full" />
              <Skeleton className="h-14 w-full" />
            </div>
          ) : domains.length > 0 ? (
            <div className="space-y-2">
              {domains.map(([key, domain]) => {
                const configured = coverageConfigured(domain)
                return (
                  <div key={key} className="rounded-lg border border-slate-200 px-3 py-2">
                    <div className="flex items-center justify-between gap-2">
                      <div className="min-w-0">
                        <p className="text-sm font-medium text-slate-800 truncate">{domainName(domain, key)}</p>
                        <p className="text-xs text-slate-500 truncate">{domainId(domain, key)}</p>
                      </div>
                      <StatusPill ok={configured} label={configured ? 'Configured' : 'Missing'} />
                    </div>
                  </div>
                )
              })}
            </div>
          ) : (
            <p className="text-sm text-slate-500">No domain registry data.</p>
          )}
        </div>
      </div>
    </Panel>
  )
}
