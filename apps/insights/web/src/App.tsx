import clsx from 'clsx'
import {
  Activity,
  AlertTriangle,
  ArrowRight,
  BarChart3,
  CircleAlert,
  Clock3,
  DollarSign,
  Gauge,
  GitBranch,
  Layers3,
  LineChart,
  LockKeyhole,
  LogOut,
  MessageSquareText,
  PieChart,
  RefreshCcw,
  Search,
  ShieldCheck,
  SlidersHorizontal,
  Table2,
  TimerReset,
  TrendingUp,
  WalletCards,
} from 'lucide-react'
import type { ReactNode } from 'react'
import { useEffect, useMemo, useState } from 'react'
import {
  Board,
  ConversationTrace,
  CostSummary,
  InsightsAccessDeniedError,
  InsightsUnauthorizedError,
  LabeledValue,
  Panel,
  Point,
  RangeKey,
  fetchConversationTrace,
  fetchInsightsBoard,
  fetchInsightsCost,
  verifyInsightsAccess,
} from './api'
import { AuthSession, clearSession, completeCallback, getSession, startSignIn } from './auth'

type AppState =
  | { name: 'checking' }
  | { name: 'signed-out' }
  | { name: 'callback' }
  | { name: 'authenticated'; session: AuthSession; initialBoard: Board }
  | { name: 'denied'; session: AuthSession }
  | { name: 'error'; message: string }

type BoardMeta = {
  id: number
  title: string
  shortTitle: string
  description: string
  icon: typeof Gauge
}

const BOARDS: BoardMeta[] = [
  {
    id: 1,
    title: 'Operations overview',
    shortTitle: 'Overview',
    description: 'Request volume, answer rate, fan-out latency, and outcome mix.',
    icon: Gauge,
  },
  {
    id: 2,
    title: 'Traffic and intent',
    shortTitle: 'Traffic',
    description: 'Question flow, intent mix, time-to-first-token, and role adoption.',
    icon: Activity,
  },
  {
    id: 3,
    title: 'Governance',
    shortTitle: 'Governance',
    description: 'Authorization checks, allow rate, denials, and decision ledger.',
    icon: ShieldCheck,
  },
  {
    id: 4,
    title: 'Agent performance',
    shortTitle: 'Agents',
    description: 'Agent latency, selection frequency, protocol mix, and call outcomes.',
    icon: TimerReset,
  },
  {
    id: 5,
    title: 'Reliability',
    shortTitle: 'Reliability',
    description: 'Circuit breakers, error rate, JVM threads, and bulkhead pressure.',
    icon: AlertTriangle,
  },
  {
    id: 6,
    title: 'Live trace',
    shortTitle: 'Trace',
    description: 'Recent fan-out latency, per-agent waterfall, and live call pressure.',
    icon: GitBranch,
  },
  {
    id: 7,
    title: 'Cost and quality',
    shortTitle: 'Cost',
    description: 'Model cost, token usage, trace volume, and evaluation signals.',
    icon: WalletCards,
  },
]

const PANEL_TITLES: Record<string, string> = {
  requests_24h: 'Requests, last 24 hours',
  answered_rate: 'Answered rate',
  agent_calls_24h: 'Agent calls, last 24 hours',
  fanout_avg_ms: 'Average fan-out',
  request_volume: 'Request volume',
  outcome_mix: 'Outcome mix',
  questions_24h: 'Questions, last 24 hours',
  ttft_p95: 'Time to first token, p95',
  questions_over_time: 'Questions over time',
  intent_mix: 'Intent mix',
  adoption_by_role: 'Adoption by role',
  allow_rate: 'Allow rate',
  decisions_24h: 'Authorization checks, last 24 hours',
  decision_mix: 'Allow and deny mix',
  decisions_by_resource: 'Checks by resource type',
  denials_by_agent: 'Denials by agent',
  authz_ledger: 'Authorization ledger',
  latency_by_agent: 'Average latency by agent',
  selection_by_agent: 'Selection frequency by agent',
  calls_by_protocol: 'Calls by protocol',
  fanout_trend: 'Fan-out trend',
  agent_calls_table: 'Agent call outcomes',
  breakers_open: 'Open circuit breakers',
  error_rate: 'Agent error rate',
  jvm_threads: 'JVM live threads',
  bulkhead_executing: 'Bulkhead executing by agent',
  bulkhead_queued: 'Bulkhead queued by agent',
  breaker_states: 'Circuit breaker states',
  trace_waterfall: 'Per-agent latency waterfall',
  fanout_p_now: 'Average fan-out, last 5 minutes',
  calls_by_agent_now: 'Recent calls by agent',
  total_cost: 'Model cost, last 30 days',
  total_tokens: 'Tokens, last 30 days',
  total_traces: 'Traces, last 30 days',
  cost_by_day: 'Cost over time',
  tokens_by_day: 'Token usage over time',
  eval_scores: 'Evaluation scores',
}

const STATUS_COPY = {
  unavailable: 'Metric unavailable',
  loading: 'Loading board',
  retry: 'Retry',
}

const RANGE_OPTIONS: Array<{ label: string; value: RangeKey }> = [
  { label: '24h', value: '24h' },
  { label: '7d', value: '7d' },
  { label: '30d', value: '30d' },
]

export default function App() {
  const [state, setState] = useState<AppState>(() => {
    if (window.location.pathname === '/callback') return { name: 'callback' }
    const session = getSession()
    return session ? { name: 'checking' } : { name: 'signed-out' }
  })

  useEffect(() => {
    let cancelled = false

    async function run() {
      try {
        if (window.location.pathname === '/callback') {
          const session = await completeCallback(window.location.search)
          window.history.replaceState({}, document.title, '/')
          const initialBoard = await verifyInsightsAccess(session)
          if (!cancelled) setState({ name: 'authenticated', session, initialBoard })
          return
        }

        const session = getSession()
        if (!session) {
          if (!cancelled) setState({ name: 'signed-out' })
          return
        }

        const initialBoard = await verifyInsightsAccess(session)
        if (!cancelled) setState({ name: 'authenticated', session, initialBoard })
      } catch (error) {
        if (cancelled) return

        const session = getSession()
        if (error instanceof InsightsAccessDeniedError && session) {
          setState({ name: 'denied', session })
          return
        }

        if (error instanceof InsightsUnauthorizedError) {
          clearSession()
          setState({ name: 'signed-out' })
          return
        }

        setState({ name: 'error', message: error instanceof Error ? error.message : 'Insights could not open.' })
      }
    }

    run()
    return () => {
      cancelled = true
    }
  }, [])

  if (state.name === 'authenticated') {
    return <InsightsShell initialBoard={state.initialBoard} session={state.session} />
  }
  if (state.name === 'denied') return <NoAccessPage session={state.session} />
  if (state.name === 'error') return <ErrorPage message={state.message} />
  if (state.name === 'checking' || state.name === 'callback') return <LoadingPage />
  return <LandingPage />
}

function LandingPage() {
  return (
    <AuthFrame>
      <section className="auth-panel" aria-labelledby="insights-login-title">
        <BrandLockup />
        <div className="auth-copy">
          <p className="eyebrow">Meridian Private Banking</p>
          <h1 id="insights-login-title">Sign in to Conduit Insights</h1>
          <p>Use Axiom single sign-on to open authorized Conduit operations analytics.</p>
        </div>
        <button type="button" className="primary-button" onClick={() => void startSignIn()}>
          Sign in with SSO
          <ArrowRight size={18} aria-hidden="true" />
        </button>
      </section>
    </AuthFrame>
  )
}

function LoadingPage() {
  return (
    <AuthFrame>
      <section className="auth-panel auth-panel-compact" aria-live="polite" aria-busy="true">
        <BrandLockup />
        <div className="loading-row">
          <span className="spinner" />
          <span>Checking Insights access</span>
        </div>
      </section>
    </AuthFrame>
  )
}

function NoAccessPage({ session }: { session: AuthSession }) {
  return (
    <AuthFrame>
      <section className="auth-panel" aria-labelledby="insights-denied-title">
        <BrandLockup />
        <div className="status-icon status-icon-warn">
          <LockKeyhole size={24} aria-hidden="true" />
        </div>
        <div className="auth-copy">
          <p className="eyebrow">Access review required</p>
          <h1 id="insights-denied-title">Insights access required</h1>
          <p>
            {session.profile.name} signed in with Axiom, but this workspace is reserved for Conduit
            Insights administrators.
          </p>
        </div>
        <button type="button" className="secondary-button" onClick={signOutLocally}>
          <LogOut size={17} aria-hidden="true" />
          Sign out
        </button>
      </section>
    </AuthFrame>
  )
}

function ErrorPage({ message }: { message: string }) {
  return (
    <AuthFrame>
      <section className="auth-panel" aria-labelledby="insights-error-title">
        <BrandLockup />
        <div className="status-icon status-icon-danger">
          <CircleAlert size={24} aria-hidden="true" />
        </div>
        <div className="auth-copy">
          <p className="eyebrow">Sign-in interrupted</p>
          <h1 id="insights-error-title">Insights did not open</h1>
          <p>{message}</p>
        </div>
        <button type="button" className="primary-button" onClick={() => void startSignIn()}>
          Try SSO again
          <ArrowRight size={18} aria-hidden="true" />
        </button>
      </section>
    </AuthFrame>
  )
}

function InsightsShell({ initialBoard, session }: { initialBoard: Board; session: AuthSession }) {
  const [activeBoardId, setActiveBoardId] = useState(1)
  const [range, setRange] = useState<RangeKey>('24h')
  const [boardsByKey, setBoardsByKey] = useState<Record<string, Board>>({ [boardCacheKey(1, '24h')]: initialBoard })
  const [loadingBoardId, setLoadingBoardId] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [cost, setCost] = useState<CostSummary | null>(null)
  const [costError, setCostError] = useState<string | null>(null)
  const [costLoading, setCostLoading] = useState(false)
  const [qualityBoard, setQualityBoard] = useState<Board | null>(null)
  const [qualityLoading, setQualityLoading] = useState(false)
  const [trace, setTrace] = useState<ConversationTrace | null>(null)
  const [traceError, setTraceError] = useState<string | null>(null)
  const [traceLoading, setTraceLoading] = useState(false)

  const activeBoardMeta = BOARDS.find((board) => board.id === activeBoardId) ?? BOARDS[0]
  const activeBoard = boardsByKey[boardCacheKey(activeBoardId, range)]
  const initials = useMemo(() => {
    return (
      session.profile.name
        .split(/\s+/)
        .filter(Boolean)
        .slice(0, 2)
        .map((part) => part[0]?.toUpperCase())
        .join('') || 'IA'
    )
  }, [session.profile.name])

  useEffect(() => {
    void loadBoard(activeBoardId, range)
    void loadCost(range)
    void loadQuality(range)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [range])

  async function openBoard(boardId: number) {
    setActiveBoardId(boardId)
    setError(null)
    await loadBoard(boardId, range)
  }

  async function loadBoard(boardId: number, nextRange: RangeKey, force = false) {
    const key = boardCacheKey(boardId, nextRange)
    if (!force && boardsByKey[key]) return

    setLoadingBoardId(boardId)
    try {
      const board = await fetchInsightsBoard(session, boardId, nextRange)
      setBoardsByKey((current) => ({ ...current, [key]: board }))
    } catch (loadError) {
      if (loadError instanceof InsightsUnauthorizedError) {
        clearSession()
        window.location.assign('/')
        return
      }
      setError(loadError instanceof Error ? loadError.message : 'Board did not load.')
    } finally {
      setLoadingBoardId(null)
    }
  }

  async function loadCost(nextRange: RangeKey) {
    setCostLoading(true)
    setCostError(null)
    try {
      setCost(await fetchInsightsCost(session, nextRange))
    } catch (loadError) {
      if (loadError instanceof InsightsUnauthorizedError) {
        clearSession()
        window.location.assign('/')
        return
      }
      setCostError(loadError instanceof Error ? loadError.message : 'Cost metrics did not load.')
    } finally {
      setCostLoading(false)
    }
  }

  async function loadQuality(nextRange: RangeKey) {
    setQualityLoading(true)
    try {
      setQualityBoard(await fetchInsightsBoard(session, 7, nextRange))
    } catch {
      setQualityBoard(null)
    } finally {
      setQualityLoading(false)
    }
  }

  async function refreshOperations() {
    await Promise.all([
      loadBoard(activeBoardId, range, true),
      loadCost(range),
      loadQuality(range),
    ])
  }

  async function loadTrace(conversationId: string) {
    const trimmed = conversationId.trim()
    if (!trimmed) return

    setTraceLoading(true)
    setTraceError(null)
    try {
      setTrace(await fetchConversationTrace(session, trimmed, 20))
    } catch (loadError) {
      if (loadError instanceof InsightsUnauthorizedError) {
        clearSession()
        window.location.assign('/')
        return
      }
      setTraceError(loadError instanceof Error ? loadError.message : 'Conversation trace did not load.')
    } finally {
      setTraceLoading(false)
    }
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <BrandLockup />
        <nav className="board-nav" aria-label="Insights boards">
          {BOARDS.map((board) => {
            const Icon = board.icon
            return (
              <button
                key={board.id}
                type="button"
                className={clsx('nav-item', board.id === activeBoardId && 'nav-item-active')}
                onClick={() => void openBoard(board.id)}
              >
                <Icon size={18} aria-hidden="true" />
                <span>{board.shortTitle}</span>
              </button>
            )
          })}
        </nav>
      </aside>

      <main className="workspace">
        <header className="topbar">
          <div>
            <p className="eyebrow">Conduit Insights</p>
            <h1>{activeBoardMeta.title}</h1>
            <p className="topbar-copy">{activeBoardMeta.description}</p>
          </div>
          <div className="user-menu" aria-label={`Signed in as ${session.profile.name}`}>
            <span className="avatar">{initials}</span>
            <span>{session.profile.name}</span>
            <button type="button" className="icon-button" onClick={signOutLocally} aria-label="Sign out">
              <LogOut size={17} aria-hidden="true" />
            </button>
          </div>
        </header>

        <section className="ops-command-bar" aria-label="Operations controls">
          <div className="range-control" aria-label="Analytics range">
            <SlidersHorizontal size={16} aria-hidden="true" />
            {RANGE_OPTIONS.map((option) => (
              <button
                key={option.value}
                type="button"
                className={clsx('range-button', option.value === range && 'range-button-active')}
                onClick={() => setRange(option.value)}
              >
                {option.label}
              </button>
            ))}
          </div>
          <button type="button" className="refresh-button" onClick={() => void refreshOperations()}>
            <RefreshCcw size={16} aria-hidden="true" />
            Refresh
          </button>
        </section>

        <div className="operations-layout">
          <section className="board-main" aria-label="Board canvas">
            <section className="board-tabs" aria-label="Board shortcuts">
              {BOARDS.map((board) => (
                <button
                  key={board.id}
                  type="button"
                  className={clsx('board-tab', board.id === activeBoardId && 'board-tab-active')}
                  onClick={() => void openBoard(board.id)}
                >
                  {board.id}
                </button>
              ))}
            </section>

            {loadingBoardId === activeBoardId && <BoardLoading />}
            {error && <BoardError message={error} onRetry={() => void openBoard(activeBoardId)} />}
            {activeBoard && loadingBoardId !== activeBoardId && !error && (
              <BoardView board={activeBoard} boardId={activeBoardId} />
            )}
          </section>

          <aside className="ops-rail" aria-label="Operations plane">
            <CostPanel cost={cost} error={costError} loading={costLoading} />
            <QualityPanel board={qualityBoard} loading={qualityLoading} />
            <TraceLookupPanel error={traceError} loading={traceLoading} onLoadTrace={loadTrace} trace={trace} />
          </aside>
        </div>
      </main>
    </div>
  )
}

function CostPanel({ cost, error, loading }: { cost: CostSummary | null; error: string | null; loading: boolean }) {
  const topModel = cost?.byModel?.[0]

  return (
    <section className="ops-card" aria-labelledby="unit-economics-title">
      <header className="ops-card-header">
        <div>
          <p className="panel-kicker">Unit economics</p>
          <h2 id="unit-economics-title">Cost controls</h2>
        </div>
        <span className="panel-icon panel-icon-green">
          <DollarSign size={17} aria-hidden="true" />
        </span>
      </header>
      {loading && <MiniState text="Loading cost metrics" />}
      {!loading && error && <MiniState tone="warn" text="Cost metrics did not load" />}
      {!loading && !error && cost && (
        <>
          <div className="economics-grid">
            <MetricTile label="Total cost" value={currency(cost.totalCostUsd)} />
            <MetricTile label="Cost per question" value={currency(cost.unitEconomics.costPerQuestionUsd)} />
            <MetricTile label="Tokens" value={compactNumber(cost.totalTokens)} />
            <MetricTile label="Tokens per question" value={compactNumber(cost.unitEconomics.tokensPerQuestion)} />
          </div>
          <div className="ops-list">
            <span>Top model</span>
            <strong>{topModel ? formatLabel(topModel.label) : 'No model data'}</strong>
          </div>
          <SliceList slices={cost.byModel} title="Cost by model" />
          <SliceList slices={cost.bySegment} title="Cost by segment" />
        </>
      )}
      {!loading && !error && !cost && <MiniState text="Cost metrics are pending" />}
    </section>
  )
}

function QualityPanel({ board, loading }: { board: Board | null; loading: boolean }) {
  const evalPanel = board?.panels.find((panel) => panel.id === 'eval_scores')
  const scores = labeledRows(evalPanel?.rows)
  const traces = board?.panels.find((panel) => panel.id === 'total_traces')
  const tokens = board?.panels.find((panel) => panel.id === 'total_tokens')

  return (
    <section className="ops-card" aria-labelledby="quality-title">
      <header className="ops-card-header">
        <div>
          <p className="panel-kicker">Continuous scores</p>
          <h2 id="quality-title">Langfuse quality</h2>
        </div>
        <span className="panel-icon panel-icon-purple">
          <TrendingUp size={17} aria-hidden="true" />
        </span>
      </header>
      {loading && <MiniState text="Loading quality scores" />}
      {!loading && (
        <>
          <div className="quality-strip">
            <MetricTile label="Traces" value={formatValue(traces?.value ?? 0, traces?.unit)} />
            <MetricTile label="Tokens" value={formatValue(tokens?.value ?? 0, tokens?.unit)} />
          </div>
          {scores.length > 0 ? (
            <div className="score-list">
              {scores.slice(0, 4).map((score) => (
                <div className="score-row" key={score.label}>
                  <span>{formatLabel(score.label)}</span>
                  <strong>{formatValue(score.value, 'score')}</strong>
                  <div className="score-track">
                    <span style={{ width: `${Math.max(Math.min(score.value, 1) * 100, 4)}%` }} />
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <MiniState text="No continuous score data yet" />
          )}
        </>
      )}
    </section>
  )
}

function TraceLookupPanel({
  error,
  loading,
  onLoadTrace,
  trace,
}: {
  error: string | null
  loading: boolean
  onLoadTrace: (conversationId: string) => Promise<void>
  trace: ConversationTrace | null
}) {
  const [conversationId, setConversationId] = useState('')

  return (
    <section className="ops-card" aria-labelledby="trace-title">
      <header className="ops-card-header">
        <div>
          <p className="panel-kicker">Decision replay</p>
          <h2 id="trace-title">Conversation trace</h2>
        </div>
        <span className="panel-icon">
          <MessageSquareText size={17} aria-hidden="true" />
        </span>
      </header>
      <form
        className="trace-form"
        onSubmit={(event) => {
          event.preventDefault()
          void onLoadTrace(conversationId)
        }}
      >
        <label htmlFor="conversation-id">Conversation ID</label>
        <div>
          <input
            id="conversation-id"
            name="conversationId"
            onChange={(event) => setConversationId(event.target.value)}
            placeholder="Paste conversation ID"
            value={conversationId}
          />
          <button type="submit" className="trace-button" disabled={loading || !conversationId.trim()}>
            <Search size={16} aria-hidden="true" />
            Load
          </button>
        </div>
      </form>
      {loading && <MiniState text="Loading conversation trace" />}
      {!loading && error && <MiniState tone="warn" text="Trace did not load" />}
      {!loading && trace && (
        <div className="trace-result">
          <div className="trace-summary">
            <MetricTile label="Requests" value={compactNumber(trace.requestCount)} />
            <MetricTile label="Events" value={compactNumber(trace.requests.reduce((sum, request) => sum + request.eventCount, 0))} />
          </div>
          <div className="trace-list">
            {trace.requests.slice(0, 4).map((request) => (
              <div key={request.requestId}>
                <span>{request.requestId}</span>
                <strong>{compactNumber(request.eventCount)} events</strong>
              </div>
            ))}
            {trace.requests.length === 0 && <span>No trace events found for this conversation.</span>}
          </div>
        </div>
      )}
      {!loading && !trace && !error && <MiniState text="Load a conversation to replay the decision trace" />}
    </section>
  )
}

function MetricTile({ label, value }: { label: string; value: string }) {
  return (
    <div className="metric-tile">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  )
}

function SliceList({ slices, title }: { slices: Array<{ label: string; costUsd: number }>; title: string }) {
  if (!slices || slices.length === 0) return null
  const max = Math.max(...slices.map((slice) => slice.costUsd), 0.0001)

  return (
    <div className="slice-list">
      <h3>{title}</h3>
      {slices.slice(0, 4).map((slice) => (
        <div className="slice-row" key={`${title}-${slice.label}`}>
          <span>{formatLabel(slice.label)}</span>
          <div className="slice-track">
            <span style={{ width: `${Math.max((slice.costUsd / max) * 100, 4)}%` }} />
          </div>
          <strong>{currency(slice.costUsd)}</strong>
        </div>
      ))}
    </div>
  )
}

function MiniState({ text, tone = 'neutral' }: { text: string; tone?: 'neutral' | 'warn' }) {
  return (
    <div className={clsx('mini-state', tone === 'warn' && 'mini-state-warn')}>
      <Clock3 size={15} aria-hidden="true" />
      <span>{text}</span>
    </div>
  )
}

function BoardLoading() {
  return (
    <section className="board-loading" aria-live="polite" aria-busy="true">
      <span className="spinner spinner-dark" />
      <span>{STATUS_COPY.loading}</span>
    </section>
  )
}

function BoardError({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <section className="board-error" aria-live="polite">
      <div>
        <h2>Board unavailable</h2>
        <p>{message}</p>
      </div>
      <button type="button" className="retry-button" onClick={onRetry}>
        <RefreshCcw size={16} aria-hidden="true" />
        {STATUS_COPY.retry}
      </button>
    </section>
  )
}

function BoardView({ board, boardId }: { board: Board; boardId: number }) {
  const panels = board.panels ?? []
  const stats = panels.filter((panel) => panel.type === 'stat')
  const visuals = panels.filter((panel) => panel.type !== 'stat')

  return (
    <section className="board-view" aria-label={`${boardId}. ${BOARDS[boardId - 1]?.title ?? 'Insights board'}`}>
      {stats.length > 0 && (
        <div className="stat-grid">
          {stats.map((panel) => (
            <PanelCard key={panel.id} panel={panel} compact />
          ))}
        </div>
      )}
      <div className="panel-grid">
        {visuals.map((panel) => (
          <PanelCard key={panel.id} panel={panel} />
        ))}
      </div>
    </section>
  )
}

function PanelCard({ compact = false, panel }: { compact?: boolean; panel: Panel }) {
  const Icon = iconForPanel(panel)

  return (
    <article className={clsx('panel-card', compact && 'panel-card-compact', panel.type === 'table' && 'panel-card-wide')}>
      <header className="panel-header">
        <div>
          <p className="panel-kicker">{labelForType(panel.type)}</p>
          <h2>{titleForPanel(panel)}</h2>
        </div>
        <span className="panel-icon">
          <Icon size={17} aria-hidden="true" />
        </span>
      </header>
      {panel.status === 'unavailable' ? <UnavailablePanel unit={panel.unit} /> : <PanelBody panel={panel} />}
    </article>
  )
}

function PanelBody({ panel }: { panel: Panel }) {
  switch (panel.type) {
    case 'stat':
      return <StatPanel panel={panel} />
    case 'area':
    case 'line':
      return <SeriesPanel panel={panel} />
    case 'donut':
      return <DonutPanel panel={panel} />
    case 'bars':
      return <BarsPanel panel={panel} />
    case 'table':
      return <TablePanel panel={panel} />
    case 'waterfall':
      return <WaterfallPanel panel={panel} />
    default:
      return <UnavailablePanel unit={panel.unit} />
  }
}

function StatPanel({ panel }: { panel: Panel }) {
  return (
    <div className="stat-panel">
      <strong>{formatValue(panel.value, panel.unit)}</strong>
      {typeof panel.delta === 'number' && <span>{formatDelta(panel.delta, panel.unit)}</span>}
    </div>
  )
}

function SeriesPanel({ panel }: { panel: Panel }) {
  const points = normalizedPoints(panel.series)
  if (points.length === 0) return <UnavailablePanel unit={panel.unit} />

  return (
    <div className="chart-block">
      <svg className="series-chart" viewBox="0 0 320 140" role="img" aria-label={titleForPanel(panel)}>
        {panel.type === 'area' && <path className="series-area" d={`${seriesPath(points, true)} L 300 124 L 20 124 Z`} />}
        <path className="series-line" d={seriesPath(points)} />
      </svg>
      <div className="chart-footer">
        <span>{formatTime(points[0].t)}</span>
        <strong>{formatValue(points[points.length - 1].v, panel.unit)}</strong>
        <span>{formatTime(points[points.length - 1].t)}</span>
      </div>
    </div>
  )
}

function DonutPanel({ panel }: { panel: Panel }) {
  const rows = labeledRows(panel.rows)
  const total = rows.reduce((sum, row) => sum + row.value, 0)
  if (rows.length === 0 || total <= 0) return <UnavailablePanel unit={panel.unit} />

  let offset = 0

  return (
    <div className="donut-layout">
      <svg className="donut-chart" viewBox="0 0 120 120" role="img" aria-label={titleForPanel(panel)}>
        <circle className="donut-track" cx="60" cy="60" r="42" />
        {rows.slice(0, 5).map((row, index) => {
          const value = Math.max(row.value, 0)
          const dash = (value / total) * 263.89
          const segment = (
            <circle
              key={row.label}
              className={`donut-segment donut-segment-${index + 1}`}
              cx="60"
              cy="60"
              r="42"
              strokeDasharray={`${dash} ${263.89 - dash}`}
              strokeDashoffset={-offset}
            />
          )
          offset += dash
          return segment
        })}
        <text x="60" y="64" textAnchor="middle">
          {formatValue(total, panel.unit)}
        </text>
      </svg>
      <RowsLegend rows={rows} unit={panel.unit} />
    </div>
  )
}

function BarsPanel({ panel }: { panel: Panel }) {
  const rows = labeledRows(panel.rows)
  if (rows.length === 0) return <UnavailablePanel unit={panel.unit} />

  const max = Math.max(...rows.map((row) => row.value), 1)

  return (
    <div className="bars-list">
      {rows.slice(0, 8).map((row) => (
        <div className="bar-row" key={row.label}>
          <div className="bar-label">
            <span>{formatLabel(row.label)}</span>
            <strong>{formatValue(row.value, panel.unit)}</strong>
          </div>
          <div className="bar-track">
            <span style={{ width: `${Math.max((row.value / max) * 100, 2)}%` }} />
          </div>
        </div>
      ))}
    </div>
  )
}

function TablePanel({ panel }: { panel: Panel }) {
  const rows = tableRows(panel.rows)
  if (rows.length === 0) return <UnavailablePanel unit={panel.unit} />

  const columns = Object.keys(rows[0]).filter((column) => column !== 'value').concat('value')

  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            {columns.map((column) => (
              <th key={column}>{formatLabel(column)}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.slice(0, 8).map((row, index) => (
            <tr key={`${panel.id}-${index}`}>
              {columns.map((column) => (
                <td key={column}>{formatCell(row[column], column === 'value' ? panel.unit : undefined)}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function WaterfallPanel({ panel }: { panel: Panel }) {
  const rows = tableRows(panel.rows)
  if (rows.length === 0) return <UnavailablePanel unit={panel.unit} />

  const values = rows.map((row) => numberFromUnknown(row.value))
  const max = Math.max(...values, 1)

  return (
    <div className="waterfall-list">
      {rows.slice(0, 7).map((row, index) => {
        const value = numberFromUnknown(row.value)
        return (
          <div className="waterfall-row" key={`${panel.id}-${index}`}>
            <span>{formatLabel(String(row.agentId ?? row.label ?? `Step ${index + 1}`))}</span>
            <div className="waterfall-track">
              <span style={{ width: `${Math.max((value / max) * 100, 3)}%` }} />
            </div>
            <strong>{formatValue(value, panel.unit)}</strong>
          </div>
        )
      })}
    </div>
  )
}

function RowsLegend({ rows, unit }: { rows: LabeledValue[]; unit: string }) {
  return (
    <div className="rows-legend">
      {rows.slice(0, 5).map((row, index) => (
        <div key={row.label}>
          <span className={`legend-dot legend-dot-${index + 1}`} />
          <span>{formatLabel(row.label)}</span>
          <strong>{formatValue(row.value, unit)}</strong>
        </div>
      ))}
    </div>
  )
}

function UnavailablePanel({ unit }: { unit: string }) {
  return (
    <div className="unavailable-panel">
      <Clock3 size={18} aria-hidden="true" />
      <span>
        {STATUS_COPY.unavailable}
        {unit && unit !== 'count' ? ` (${unit})` : ''}
      </span>
    </div>
  )
}

function AuthFrame({ children }: { children: ReactNode }) {
  return (
    <main className="auth-frame">
      <div className="auth-background" />
      {children}
    </main>
  )
}

function BrandLockup() {
  return (
    <div className="brand-lockup">
      <div className="brand-mark">C</div>
      <div>
        <p>Conduit Insights</p>
        <span>Axiom secured analytics</span>
      </div>
      <ShieldCheck className="brand-shield" size={20} aria-hidden="true" />
    </div>
  )
}

function signOutLocally() {
  const session = getSession()
  clearSession()
  if (session?.idToken) {
    const logoutUrl = new URL('http://localhost:8084/connect/logout')
    logoutUrl.searchParams.set('id_token_hint', session.idToken)
    logoutUrl.searchParams.set('post_logout_redirect_uri', `${window.location.origin}/`)
    window.location.assign(logoutUrl.toString())
    return
  }
  window.location.assign('/')
}

function iconForPanel(panel: Panel) {
  if (panel.type === 'stat') return Gauge
  if (panel.type === 'donut') return PieChart
  if (panel.type === 'bars') return BarChart3
  if (panel.type === 'table') return Table2
  if (panel.type === 'waterfall') return Layers3
  return LineChart
}

function labelForType(type: Panel['type']) {
  const labels: Record<Panel['type'], string> = {
    area: 'Trend',
    bars: 'Breakdown',
    donut: 'Mix',
    line: 'Trend',
    stat: 'Metric',
    table: 'Ledger',
    waterfall: 'Waterfall',
  }
  return labels[type]
}

function titleForPanel(panel: Panel) {
  return PANEL_TITLES[panel.id] ?? panel.title
}

function boardCacheKey(boardId: number, range: RangeKey) {
  return `${range}:${boardId}`
}

function labeledRows(rows: Panel['rows']): LabeledValue[] {
  if (!rows) return []
  return rows
    .map((row) => {
      const value = row as Partial<LabeledValue>
      return {
        label: typeof value.label === 'string' && value.label ? value.label : 'Unlabeled',
        value: numberFromUnknown(value.value),
      }
    })
    .filter((row) => Number.isFinite(row.value))
    .sort((a, b) => b.value - a.value)
}

function tableRows(rows: Panel['rows']): Array<Record<string, unknown>> {
  if (!rows) return []
  return rows
    .filter((row): row is Record<string, unknown> => row != null && typeof row === 'object')
    .map((row) => ({ ...row }))
}

function normalizedPoints(series: Point[] | undefined) {
  if (!series) return []
  const values = series.filter((point) => Number.isFinite(point.v)).sort((a, b) => a.t - b.t)
  if (values.length === 0) return []

  const min = Math.min(...values.map((point) => point.v))
  const max = Math.max(...values.map((point) => point.v))
  const spread = max - min || 1
  const xStep = values.length > 1 ? 280 / (values.length - 1) : 0

  return values.map((point, index) => ({
    ...point,
    x: 20 + index * xStep,
    y: 124 - ((point.v - min) / spread) * 104,
  }))
}

function seriesPath(points: Array<Point & { x: number; y: number }>, area = false) {
  const path = points.map((point, index) => `${index === 0 ? 'M' : 'L'} ${point.x.toFixed(2)} ${point.y.toFixed(2)}`)
  if (area && points.length === 1) return `${path[0]} L ${points[0].x.toFixed(2)} 124`
  return path.join(' ')
}

function numberFromUnknown(value: unknown) {
  if (typeof value === 'number') return value
  if (typeof value === 'string') {
    const parsed = Number(value)
    return Number.isFinite(parsed) ? parsed : 0
  }
  return 0
}

function formatValue(value: unknown, unit?: string) {
  if (typeof value === 'string') return value
  const number = numberFromUnknown(value)

  if (unit === '%') return `${compactNumber(number)}%`
  if (unit === 'ms') return `${compactNumber(number)} ms`
  if (unit === 's') return `${compactNumber(number)} s`
  if (unit === 'USD') return currency(number)
  if (unit === 'req/s') return `${compactNumber(number)} req/s`
  if (unit === 'q/s') return `${compactNumber(number)} q/s`
  if (unit === 'score') return compactNumber(number)
  if (unit === 'state') return compactNumber(number)
  return compactNumber(number)
}

function formatDelta(value: number, unit: string) {
  const sign = value > 0 ? '+' : ''
  return `${sign}${formatValue(value, unit)} vs prior window`
}

function compactNumber(value: number) {
  if (!Number.isFinite(value)) return '0'
  if (Math.abs(value) >= 1000) {
    return new Intl.NumberFormat('en-US', { maximumFractionDigits: 1, notation: 'compact' }).format(value)
  }
  if (Number.isInteger(value)) return new Intl.NumberFormat('en-US').format(value)
  return new Intl.NumberFormat('en-US', { maximumFractionDigits: 2 }).format(value)
}

function currency(value: number) {
  return new Intl.NumberFormat('en-US', {
    currency: 'USD',
    maximumFractionDigits: value >= 100 ? 0 : 2,
    style: 'currency',
  }).format(value)
}

function formatTime(epochSeconds: number) {
  return new Intl.DateTimeFormat('en-US', { hour: 'numeric', minute: '2-digit' }).format(new Date(epochSeconds * 1000))
}

function formatLabel(value: string) {
  return value
    .replace(/_/g, ' ')
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .replace(/\b\w/g, (char) => char.toUpperCase())
}

function formatCell(value: unknown, unit?: string) {
  if (unit && (typeof value === 'number' || typeof value === 'string')) return formatValue(value, unit)
  if (typeof value === 'number') return compactNumber(value)
  if (typeof value === 'string') return formatLabel(value)
  if (value == null) return '-'
  return String(value)
}
