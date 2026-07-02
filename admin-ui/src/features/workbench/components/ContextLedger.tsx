import { clsx } from 'clsx'
import { Database } from 'lucide-react'
import type { StatusTone, TraceEvent } from '../types'
import { formatDuration, getString } from '../utils/format'
import { latestOf } from '../utils/selectors'
import { Panel } from './Panel'

interface ContextLedgerProps {
  userId: string
  conversationId: string
  events: TraceEvent[]
  traceSubscribers?: number
}

export function ContextLedger({
  userId,
  conversationId,
  events,
  traceSubscribers,
}: ContextLedgerProps) {
  const intent = latestOf(events, 'intent_classified')
  const entitlement = latestOf(events, 'entitlement_check')
  const requestComplete = latestOf(events, 'request_complete')
  const agentsResolved = latestOf(events, 'agents_resolved')
  const latestEvent = events.length > 0 ? events[events.length - 1] : undefined

  const selected = Array.isArray(agentsResolved?.data?.selected)
    ? agentsResolved.data.selected.length
    : 0

  return (
    <Panel title="Context Ledger" icon={Database}>
      <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 divide-y md:divide-y-0 md:divide-x divide-slate-100">
        <LedgerCell label="Principal" value={userId || 'Unknown'} />
        <LedgerCell label="Conversation" value={conversationId.slice(0, 18)} />
        <LedgerCell
          label="Intent"
          value={getString(intent?.data?.intent, 'Pending')}
          meta={intent?.data?.confidence ? `${Math.round(Number(intent.data.confidence) * 100)}%` : undefined}
        />
        <LedgerCell
          label="Access"
          value={entitlement ? (entitlement.data?.allowed ? 'Allowed' : 'Denied') : 'Pending'}
          meta={getString(entitlement?.data?.relationshipId, undefined)}
          tone={entitlement?.data?.allowed === false ? 'red' : entitlement ? 'green' : 'slate'}
        />
        <LedgerCell
          label="Agents"
          value={selected ? String(selected) : 'Pending'}
          meta={getString(requestComplete?.data?.successCount, undefined)}
        />
        <LedgerCell label="Duration" value={formatDuration(requestComplete?.data?.totalMs)} />
        <LedgerCell
          label="Trace"
          value={traceSubscribers === undefined ? 'Pending' : `${traceSubscribers} subscriber${traceSubscribers === 1 ? '' : 's'}`}
        />
        <LedgerCell
          label="Request"
          value={latestEvent?.requestId ? latestEvent.requestId.slice(0, 12) : 'Pending'}
        />
      </div>
    </Panel>
  )
}

interface LedgerCellProps {
  label: string
  value: string
  meta?: string
  tone?: StatusTone
}

function LedgerCell({ label, value, meta, tone = 'slate' }: LedgerCellProps) {
  return (
    <div className="px-4 py-3 min-w-0">
      <p className="text-[11px] font-medium uppercase tracking-normal text-slate-400">{label}</p>
      <p className={clsx(
        'text-sm font-semibold mt-1 truncate',
        tone === 'green' && 'text-green-700',
        tone === 'red' && 'text-red-700',
        tone === 'slate' && 'text-slate-800',
      )}>
        {value}
      </p>
      {meta && <p className="text-xs text-slate-500 mt-0.5 truncate">{meta}</p>}
    </div>
  )
}
