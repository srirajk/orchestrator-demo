import { Activity, Bot, CheckCircle2, CircleDot, FileClock, LockKeyhole, Radio, Sparkles } from 'lucide-react'
import { Badge } from '../../../components/ui/Badge'
import type { TraceEvent } from '../types'
import { formatTime } from '../utils/format'
import { eventDetail, eventTitle, eventTone } from '../utils/traceEvents'
import { Panel } from './Panel'
import { StatusPill } from './StatusPill'

function eventIcon(type: string) {
  if (type === 'request_complete') return CheckCircle2
  if (type === 'entitlement_check') return LockKeyhole
  if (type.startsWith('agent')) return Bot
  if (type === 'synthesis_start') return Sparkles
  if (type === 'intent_classified') return CircleDot
  return Activity
}

interface TraceRailProps {
  events: TraceEvent[]
  connected: boolean
  error?: string | null
}

export function TraceRail({ events, connected, error }: TraceRailProps) {
  return (
    <Panel
      title="Trace"
      icon={Radio}
      action={<StatusPill ok={connected} label={connected ? 'Live' : 'Offline'} />}
    >
      <div className="h-[616px] overflow-y-auto">
        {events.length === 0 ? (
          <div className="px-4 py-8 text-center">
            <FileClock size={28} className="mx-auto text-slate-300 mb-2" />
            <p className="text-sm font-medium text-slate-700">No trace events</p>
            <p className="text-xs text-slate-500 mt-1">{error || 'The next turn will populate this rail.'}</p>
          </div>
        ) : (
          <div className="divide-y divide-slate-100">
            {[...events].reverse().map((event, index) => {
              const Icon = eventIcon(event.type)
              const detail = eventDetail(event)
              return (
                <div key={`${event.requestId}-${event.type}-${event.timestamp}-${index}`} className="px-4 py-3 hover:bg-slate-50">
                  <div className="flex items-start gap-3">
                    <div className="mt-0.5 h-7 w-7 rounded-md bg-slate-100 flex items-center justify-center shrink-0">
                      <Icon size={14} className="text-slate-500" />
                    </div>
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2 mb-1 min-w-0">
                        <Badge color={eventTone(event.type)} className="shrink-0">
                          {event.type.replace(/_/g, ' ')}
                        </Badge>
                        <span className="text-[11px] text-slate-400 shrink-0">{formatTime(event.timestamp)}</span>
                      </div>
                      <p className="text-sm font-medium text-slate-800 leading-5">{eventTitle(event)}</p>
                      {detail && <p className="text-xs text-slate-500 mt-1 line-clamp-2">{detail}</p>}
                    </div>
                  </div>
                </div>
              )
            })}
          </div>
        )}
      </div>
    </Panel>
  )
}
