import { useMemo } from 'react'
import {
  Check,
  X,
  Loader2,
  Sparkles,
  Compass,
  ListChecks,
  ShieldCheck,
  CircleDot,
  Bot,
  PanelRightClose,
  PanelRightOpen,
} from 'lucide-react'
import type { TraceEvent } from '../lib/gatewayTrace'
import type { TraceStatus } from '../hooks/useTraceStream'

/** Compact number/string coercers + duration formatter (mirror of gateway-client presenters). */
function str(v: unknown, fallback = ''): string {
  return v === undefined || v === null || v === '' ? fallback : String(v)
}
function ms(v: unknown): string {
  const n = typeof v === 'number' ? v : Number(v)
  if (!Number.isFinite(n)) return ''
  return n >= 1000 ? `${(n / 1000).toFixed(1)}s` : `${Math.round(n)}ms`
}

type RowTone = 'green' | 'red' | 'slate' | 'blue' | 'indigo' | 'yellow'

interface Row {
  key: string
  icon: JSX.Element
  label: string
  detail?: string
  tone: RowTone
}

const TONE_CLASS: Record<RowTone, string> = {
  green: 'text-emerald-600',
  red: 'text-red-600',
  slate: 'text-slate-500',
  blue: 'text-sky-600',
  indigo: 'text-indigo-600',
  yellow: 'text-amber-600',
}

/** Maps ordered trace frames to legible pipeline rows: intent → resolve → gates → agents → answer. */
function toRows(events: TraceEvent[]): Row[] {
  const rows: Row[] = []
  events.forEach((evt, i) => {
    const data = evt.data ?? {}
    const key = `${evt.type}-${i}`
    switch (evt.type) {
      case 'request_start':
        rows.push({ key, icon: <CircleDot size={15} />, tone: 'slate', label: 'Question received', detail: String(data.prompt ?? '') })
        break
      case 'intent_classified': {
        const conf = typeof data.confidence === 'number' ? ` · ${Math.round(data.confidence * 100)}%` : ''
        rows.push({ key, icon: <Sparkles size={15} />, tone: 'yellow', label: `Intent: ${str(data.intent, 'classified')}`, detail: `${str(data.reasoning)}${conf}`.trim() })
        break
      }
      case 'agents_resolved': {
        const sel = Array.isArray(data.selected) ? data.selected.length : 0
        const filt = Array.isArray(data.filtered) ? data.filtered.length : 0
        rows.push({ key, icon: <Compass size={15} />, tone: 'blue', label: `Resolved ${sel} agent${sel === 1 ? '' : 's'}${filt ? `, ${filt} filtered` : ''}` })
        break
      }
      case 'gate': {
        const deny = data.effect === 'deny'
        rows.push({
          key,
          icon: deny ? <X size={15} strokeWidth={3} /> : <Check size={15} strokeWidth={3} />,
          tone: deny ? 'red' : 'green',
          label: `${String(data.gate ?? 'gate')} ${deny ? 'denied' : 'passed'}`,
          detail: `${String(data.reason ?? '')}${data.agent ? `  ·  ${String(data.agent)}` : ''}`,
        })
        break
      }
      case 'entitlement_check': {
        const denied = data.allowed === false
        rows.push({
          key,
          icon: denied ? <X size={15} strokeWidth={3} /> : <ShieldCheck size={15} />,
          tone: denied ? 'red' : 'green',
          label: `${denied ? 'Access denied' : 'Access allowed'} for ${str(data.relationshipId, 'resource')}`,
          detail: str(data.reason, str(data.source)),
        })
        break
      }
      case 'check_denied':
        // The structured `gate` frame already renders this deny; skip to avoid a duplicate row.
        break
      case 'agent_start':
        rows.push({ key, icon: <Bot size={15} />, tone: 'blue', label: `${str(data.agentId, 'Agent')} started` })
        break
      case 'agent_complete': {
        const failed = str(data.status) === 'failed'
        rows.push({ key, icon: <Bot size={15} />, tone: failed ? 'red' : 'blue', label: `${str(data.agentId, 'Agent')} ${str(data.status, 'complete')}`, detail: `${ms(data.durationMs)}${data.dataPreview ? ` · ${str(data.dataPreview)}` : ''}`.trim() })
        break
      }
      case 'synthesis_start':
        rows.push({ key, icon: <ListChecks size={15} />, tone: 'indigo', label: `Synthesizing ${str(data.successCount, '0')}/${str(data.agentCount, '0')}` })
        break
      case 'request_complete':
        rows.push({ key, icon: <Check size={15} strokeWidth={3} />, tone: 'green', label: `Answer ready${data.totalMs ? ` in ${ms(data.totalMs)}` : ''}` })
        break
      default:
        break
    }
  })
  return rows
}

interface TraceRailProps {
  events: TraceEvent[]
  status: TraceStatus
  collapsed: boolean
  onToggle: () => void
}

/**
 * The Conduit glass-box rail: a collapsible, conversation-scoped vertical view of the request
 * pipeline — intent → resolve → per-gate authorization decisions (✓/✗ + reason) → agents →
 * answer. Subscribed via {@link useTraceStream}; the gate rows are the product's trust story.
 */
export function TraceRail({ events, status, collapsed, onToggle }: TraceRailProps) {
  const rows = useMemo(() => toRows(events), [events])

  if (collapsed) {
    return (
      <div className="w-10 shrink-0 border-l border-line bg-panel flex flex-col items-center py-3">
        <button
          onClick={onToggle}
          aria-label="Show decision trace"
          title="Show decision trace"
          className="p-1.5 rounded hover:bg-canvas text-slate-500 focus:outline-none focus:ring-2 focus:ring-indigo-400"
        >
          <PanelRightOpen size={18} />
        </button>
      </div>
    )
  }

  return (
    <aside className="w-80 shrink-0 border-l border-line bg-panel flex flex-col overflow-hidden" aria-label="Decision trace">
      <div className="border-b border-line px-4 py-3 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <ShieldCheck size={16} className="text-indigo-600" />
          <h2 className="section-heading">Decision trace</h2>
        </div>
        <button
          onClick={onToggle}
          aria-label="Hide decision trace"
          title="Hide decision trace"
          className="p-1 rounded hover:bg-canvas text-slate-500 focus:outline-none focus:ring-2 focus:ring-indigo-400"
        >
          <PanelRightClose size={18} />
        </button>
      </div>

      <div className="flex-1 overflow-y-auto px-3 py-3 text-sm">
        {status === 'error' && (
          <p className="text-red-600 px-1 py-2">Trace stream unavailable.</p>
        )}

        {rows.length === 0 && status !== 'error' && (
          <div className="flex flex-col items-center justify-center h-full text-center text-slate-400 gap-2 px-4">
            {status === 'connecting' ? (
              <>
                <Loader2 size={18} className="animate-spin" />
                <p>Connecting to the glass box…</p>
              </>
            ) : (
              <>
                <ShieldCheck size={20} className="text-slate-300" />
                <p>Ask a question to see how the answer is authorized and assembled — every gate, live.</p>
              </>
            )}
          </div>
        )}

        {rows.length > 0 && (
          <ol className="relative border-l border-line ml-2 space-y-1">
            {rows.map((row) => (
              <li key={row.key} className="ml-3 pl-3 py-1.5 relative">
                <span
                  className={`absolute -left-[7px] top-2 flex items-center justify-center bg-panel ${TONE_CLASS[row.tone]}`}
                >
                  {row.icon}
                </span>
                <p className="font-medium text-ink-900 capitalize leading-tight">{row.label}</p>
                {row.detail && (
                  <p className="text-xs text-slate-500 mt-0.5 break-words normal-case">{row.detail}</p>
                )}
              </li>
            ))}
          </ol>
        )}
      </div>
    </aside>
  )
}
