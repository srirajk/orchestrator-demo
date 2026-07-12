import clsx from 'clsx'
import { ArrowRight, CircleAlert, LockKeyhole, LogOut } from 'lucide-react'
import type { ReactNode } from 'react'
import { useEffect, useMemo, useState } from 'react'
import {
  Board,
  ConversationTrace,
  CostSlice,
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

type ViewId = 'ov' | 'tr' | 'ag' | 'or' | 'ec' | 'aq' | 'us' | 'ua' | 'iv'

type ViewMeta = {
  id: ViewId
  icon: string
  title: string
  question?: string
  subtitle: string
}

type TraceEventRecord = {
  type?: unknown
  requestId?: unknown
  conversationId?: unknown
  timestamp?: unknown
  data?: unknown
}

type TableRecord = Record<string, unknown>

const RANGE_OPTIONS: RangeKey[] = ['24h', '7d', '30d']

const OPERATE_VIEWS: ViewMeta[] = [
  { id: 'ov', icon: '◎', title: 'Overview', question: 'working?', subtitle: 'Is the gateway working? — live state across all traffic' },
  { id: 'tr', icon: '⛨', title: 'Trust & Governance', question: 'safe?', subtitle: 'Is it safe? — the three-gate ABAC in action' },
  { id: 'ag', icon: '⚙', title: 'Agents & Pipeline', question: 'running?', subtitle: 'How is it running? — fleet health and where the time goes' },
  { id: 'or', icon: '⌘', title: 'Orchestration', question: 'how does it decide?', subtitle: 'The decision intelligence — intent, fan-out, and outcomes' },
  { id: 'ec', icon: '◆', title: 'Economics', question: 'cost?', subtitle: 'What is it costing? — cost per question, by segment / model / user' },
]

const EVALUATE_VIEWS: ViewMeta[] = [
  { id: 'aq', icon: '◈', title: 'Answer quality', question: 'trust?', subtitle: 'Can you trust the answers? — continuous, independent evaluation of every response' },
]

const AUDIT_VIEWS: ViewMeta[] = [
  { id: 'us', icon: '◉', title: 'By user — spend & quality', subtitle: 'Per-principal cost and answer quality' },
  { id: 'ua', icon: '◍', title: 'By user — audit & conversations', subtitle: 'Per-principal authorization trail and conversation replay' },
  { id: 'iv', icon: '⌕', title: 'Decision replay', subtitle: 'What happened here? — replay any decision by conversation id' },
]

const ALL_VIEWS = [...OPERATE_VIEWS, ...EVALUATE_VIEWS, ...AUDIT_VIEWS]

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
    return <InsightsPlane initialBoard={state.initialBoard} session={state.session} />
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
          <span className="button-label">Sign in with SSO</span>
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
          <p className="eyebrow">No Insights access</p>
          <h1 id="insights-denied-title">You don't have Insights access</h1>
          <p>{session.profile.name} signed in with Axiom, but this operations plane is limited to Insights administrators.</p>
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
          <span className="button-label">Try SSO again</span>
          <ArrowRight size={18} aria-hidden="true" />
        </button>
      </section>
    </AuthFrame>
  )
}

function InsightsPlane({ initialBoard, session }: { initialBoard: Board; session: AuthSession }) {
  const [activeView, setActiveView] = useState<ViewId>('ov')
  const [range, setRange] = useState<RangeKey>('7d')
  const [boards, setBoards] = useState<Record<number, Board>>({ 1: initialBoard })
  const [cost, setCost] = useState<CostSummary | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [syncedAt, setSyncedAt] = useState<Date | null>(null)
  const [trace, setTrace] = useState<ConversationTrace | null>(null)
  const [traceLoading, setTraceLoading] = useState(false)
  const [traceError, setTraceError] = useState<string | null>(null)
  const [selectedUser, setSelectedUser] = useState<string | null>(null)

  const activeMeta = ALL_VIEWS.find((view) => view.id === activeView) ?? ALL_VIEWS[0]
  const initials = useMemo(() => initialsFor(session.profile.name), [session.profile.name])

  useEffect(() => {
    if (!selectedUser && cost?.byUser[0]) setSelectedUser(cost.byUser[0].label)
  }, [cost, selectedUser])

  useEffect(() => {
    let cancelled = false

    async function load() {
      setLoading(true)
      setError(null)
      try {
        const [nextBoards, nextCost] = await Promise.all([
          Promise.all([1, 2, 3, 4, 5, 6, 7, 8].map(async (id) => [id, await fetchInsightsBoard(session, id, range)] as const)),
          fetchInsightsCost(session, range),
        ])

        if (cancelled) return
        setBoards(Object.fromEntries(nextBoards))
        setCost(nextCost)
        setSyncedAt(new Date())
      } catch (loadError) {
        if (cancelled) return
        if (loadError instanceof InsightsUnauthorizedError) {
          clearSession()
          window.location.assign('/')
          return
        }
        if (loadError instanceof InsightsAccessDeniedError) {
          setError('Insights access was denied for this session.')
          return
        }
        setError(loadError instanceof Error ? loadError.message : 'Insights data did not load.')
      } finally {
        if (!cancelled) setLoading(false)
      }
    }

    void load()
    return () => {
      cancelled = true
    }
  }, [range, session])

  async function loadTrace(conversationId: string) {
    const trimmed = conversationId.trim()
    if (!trimmed) return

    setTraceLoading(true)
    setTraceError(null)
    try {
      setTrace(await fetchConversationTrace(session, trimmed, 20))
      setActiveView('iv')
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
    <div className="app">
      <aside className="rail">
        <div className="brand">
          <div className="mark">C</div>
          <div>
            <h1>Conduit Insights</h1>
            <p>Operations plane</p>
          </div>
        </div>

        <NavGroup label="Operate" views={OPERATE_VIEWS} activeView={activeView} onSelect={setActiveView} />
        <NavGroup label="Evaluate" views={EVALUATE_VIEWS} activeView={activeView} onSelect={setActiveView} />
        <NavGroup label="Audit & investigate" views={AUDIT_VIEWS} activeView={activeView} onSelect={setActiveView} />

        <div className="rail-foot">
          <div className="clr">
            <span className="dot" />
            Viewing at: highest classification
          </div>
          <div className="who">
            <span className="av">{initials}</span>
            <div>
              {session.profile.name}
              <br />
              <span>{session.profile.email ?? session.profile.subject}</span>
            </div>
          </div>
          <button type="button" className="logout-link" onClick={signOutLocally}>
            Sign out
          </button>
        </div>
      </aside>

      <main className="main">
        <div className="top">
          <div>
            <h2>{activeMeta.title}</h2>
            <div className="sub">{activeMeta.subtitle}</div>
          </div>
          <div className="spacer" />
          <div className="fresh">
            <span className="pulse" />
            Live · {syncedAt ? `synced ${relativeSeconds(syncedAt)}s ago` : loading ? 'syncing' : 'waiting'}
          </div>
          <div className="seg" aria-label="Analytics range">
            {RANGE_OPTIONS.map((option) => (
              <button
                key={option}
                type="button"
                className={clsx(option === range && 'on')}
                onClick={() => setRange(option)}
              >
                {option}
              </button>
            ))}
          </div>
        </div>

        {error && <SystemNotice tone="danger" tag="Data unavailable" message={error} />}
        {!error && loading && <SystemNotice tag="Syncing" message="Loading live Insights telemetry from the gateway." />}

        <section className={clsx('view', activeView === 'ov' && 'on')}>
          <OverviewView boards={boards} cost={cost} range={range} onNavigate={setActiveView} onLoadTrace={loadTrace} />
        </section>
        <section className={clsx('view', activeView === 'tr' && 'on')}>
          <TrustView boards={boards} onLoadTrace={loadTrace} />
        </section>
        <section className={clsx('view', activeView === 'ag' && 'on')}>
          <AgentsView boards={boards} onNavigate={setActiveView} />
        </section>
        <section className={clsx('view', activeView === 'or' && 'on')}>
          <OrchestrationView boards={boards} />
        </section>
        <section className={clsx('view', activeView === 'ec' && 'on')}>
          <EconomicsView boards={boards} cost={cost} range={range} />
        </section>
        <section className={clsx('view', activeView === 'aq' && 'on')}>
          <AnswerQualityView boards={boards} cost={cost} onNavigate={setActiveView} />
        </section>
        <section className={clsx('view', activeView === 'us' && 'on')}>
          <ByUserSpendQualityView cost={cost} boards={boards} selectedLabel={selectedUser} onSelectUser={setSelectedUser} />
        </section>
        <section className={clsx('view', activeView === 'ua' && 'on')}>
          <ByUserAuditView cost={cost} boards={boards} selectedLabel={selectedUser} onSelectUser={setSelectedUser} onNavigate={setActiveView} onLoadTrace={loadTrace} />
        </section>
        <section className={clsx('view', activeView === 'iv' && 'on')}>
          <InvestigateView trace={trace} loading={traceLoading} error={traceError} onLoadTrace={loadTrace} />
        </section>
      </main>
    </div>
  )
}

function NavGroup({
  activeView,
  label,
  onSelect,
  views,
}: {
  activeView: ViewId
  label: string
  onSelect: (view: ViewId) => void
  views: ViewMeta[]
}) {
  return (
    <>
      <div className="nav-label">{label}</div>
      <nav className="nav" aria-label={label}>
        {views.map((view) => (
          <button
            key={view.id}
            type="button"
            className={clsx(activeView === view.id && 'on')}
            onClick={() => onSelect(view.id)}
          >
            <span className="ic">{view.icon}</span>
            {view.title}
            {view.question && <span className="q">{view.question}</span>}
          </button>
        ))}
      </nav>
    </>
  )
}

function OverviewView({
  boards,
  cost,
  onNavigate,
  onLoadTrace,
  range,
}: {
  boards: Record<number, Board>
  cost: CostSummary | null
  onNavigate: (view: ViewId) => void
  onLoadTrace: (conversationId: string) => void
  range: RangeKey
}) {
  const overview = boards[1]
  const reliability = boards[5]
  const agents = boards[4]
  const governance = boards[3]
  const requests = panel(overview, 'requests_24h')
  const answerRate = panel(overview, 'answered_rate')
  const fanout = panel(overview, 'fanout_avg_ms')
  const agentCalls = panel(overview, 'agent_calls_24h')
  const breakers = panel(reliability, 'breakers_open')
  const errorRate = panel(reliability, 'error_rate')
  const outcomeMix = labeledRows(panel(overview, 'outcome_mix')?.rows)
  const ledgerRows = tableRows(panel(governance, 'authz_ledger')?.rows)
  const slowAgent = topRow(labeledRows(panel(agents, 'latency_by_agent')?.rows))
  const openBreakers = numberValue(breakers)
  const isNominal = openBreakers === 0 && numberValue(errorRate) < 1

  return (
    <>
      <div className="strip">
        <div className={clsx('card health', !isNominal && 'health-warn')}>
          <div className="h">
            <span className="pulse" />
            System health
          </div>
          <div className="big">{isNominal ? 'All systems nominal' : 'Attention required'}</div>
          <div className="s">
            {formatValue(agentCalls?.value, agentCalls?.unit)} agent calls · {formatValue(breakers?.value, breakers?.unit)} breakers open
          </div>
        </div>
        <KpiCard label="Requests" panel={requests} />
        <KpiCard label="Answer rate" panel={answerRate} />
        <KpiCard label="p95 latency" value={formatValue(fanout?.value, fanout?.unit)} detail="fan-out average" />
        <KpiCard label="Cost / question" value={cost ? currency(cost.unitEconomics.costPerQuestionUsd) : collecting()} detail={cost ? `${compactNumber(cost.totalTokens)} tokens` : 'cost endpoint'} />
      </div>

      {slowAgent ? (
        <SystemNotice
          action="Investigate →"
          message={`${formatLabel(slowAgent.label)} is the slowest live agent at ${formatValue(slowAgent.value, 'ms')}.`}
          onAction={() => onNavigate('ag')}
          tag="Needs attention"
        />
      ) : (
        <SystemNotice tone="nom" tag="All nominal" message="No live agent latency alerts are available for this window." />
      )}

      <div className="grid">
        <PanelShell className="col8" title="Request volume" badge="Live">
          <AreaChart points={panel(overview, 'request_volume')?.series ?? []} color="gold" />
        </PanelShell>
        <PanelShell className="col4" title="Outcome mix" caption={`Current range · ${range}`}>
          <Donut rows={outcomeMix} />
        </PanelShell>
        <PanelShell className="col12" title="Live decision feed" badge="Live">
          {ledgerRows.length > 0 ? (
            <div className="feed">
              {ledgerRows.slice(0, 7).map((row, index) => (
                <DecisionRow key={rowKey(row, index)} row={row} onClick={() => onNavigate('iv')} onLoadTrace={onLoadTrace} />
              ))}
            </div>
          ) : (
            <EmptyState>No decision-feed rows for this range.</EmptyState>
          )}
        </PanelShell>
        <PanelShell className="col12" title="Outcomes & failure taxonomy" caption="conduit_request_outcome_total — every request classified by how it ended">
          {outcomeMix.length > 0 ? (
            <RunboxGrid rows={outcomeMix} />
          ) : (
            <EmptyState>Outcome taxonomy has no samples for this range.</EmptyState>
          )}
        </PanelShell>
      </div>
    </>
  )
}

function TrustView({ boards, onLoadTrace }: { boards: Record<number, Board>; onLoadTrace: (conversationId: string) => void }) {
  const governance = boards[3]
  const decisions = panel(governance, 'decisions_24h')
  const allowRate = panel(governance, 'allow_rate')
  const decisionMix = labeledRows(panel(governance, 'decision_mix')?.rows)
  const resources = labeledRows(panel(governance, 'decisions_by_resource')?.rows)
  const deniedAgents = labeledRows(panel(governance, 'denials_by_agent')?.rows)
  const ledgerRows = tableRows(panel(governance, 'authz_ledger')?.rows)
  const denied = decisionMix.find((row) => /deny|denied/i.test(row.label))

  return (
    <>
      <SystemNotice tone="nom" tag="All nominal" message="Policy panels are sourced from live authorization telemetry; no fabricated identifiers are displayed." />
      <div className="strip trust-strip">
        <KpiCard label="Authz decisions" panel={decisions} detail="segment · class · coverage" />
        <KpiCard label="Denied" value={denied ? compactNumber(denied.value) : collecting()} detail={denied ? 'live deny count' : 'decision mix'} />
        <KpiCard label="Allow rate" panel={allowRate} />
      </div>
      <div className="grid grid-gap-top">
        <PanelShell className="col6" title="Denials by gate" caption="Where the three-gate ABAC stopped a request">
          <Bars rows={deniedAgents} empty="No denial-by-gate data for this range." />
        </PanelShell>
        <PanelShell className="col6" title="Denials by segment" caption="Which books hit the wall">
          <Bars rows={resources} empty="No denial-by-segment data for this range." />
        </PanelShell>
        <PanelShell className="col12" title="Authorization ledger" badge="Live">
          {ledgerRows.length > 0 ? (
            <div className="feed">
              {ledgerRows.slice(0, 8).map((row, index) => (
                <DecisionRow key={rowKey(row, index)} row={row} onLoadTrace={onLoadTrace} />
              ))}
            </div>
          ) : (
            <EmptyState>No authorization-ledger rows for this range.</EmptyState>
          )}
        </PanelShell>
      </div>
    </>
  )
}

function AgentsView({ boards, onNavigate }: { boards: Record<number, Board>; onNavigate: (view: ViewId) => void }) {
  const agents = boards[4]
  const reliability = boards[5]
  const traffic = boards[2]
  const fleetRows = tableRows(panel(agents, 'agent_calls_table')?.rows)
  const latency = labeledRows(panel(agents, 'latency_by_agent')?.rows)
  const selection = labeledRows(panel(agents, 'selection_by_agent')?.rows)
  const intent = labeledRows(panel(traffic, 'intent_mix')?.rows)
  const breakerRows = tableRows(panel(reliability, 'breaker_states')?.rows)
  const slowest = topRow(latency)

  return (
    <>
      {slowest ? (
        <SystemNotice
          action="View agent →"
          message={`${formatLabel(slowest.label)} is the highest-latency agent at ${formatValue(slowest.value, 'ms')}.`}
          onAction={() => onNavigate('iv')}
          tag="Needs attention"
        />
      ) : (
        <SystemNotice tone="nom" tag="All nominal" message="No agent-fleet telemetry for this range." />
      )}
      <div className="grid">
        <PanelShell className="col12" title="Agent fleet" caption="Calls · success · p95 · current range">
          {fleetRows.length > 0 ? (
            <div className="feed">
              {fleetRows.slice(0, 6).map((row, index) => (
                <AgentFleetRow key={rowKey(row, index)} row={row} />
              ))}
            </div>
          ) : (
            <EmptyState>No agent-outcome rows for this range.</EmptyState>
          )}
        </PanelShell>
        <PanelShell className="col7" title="Runtime & resilience" badge="↗ Grafana">
          <div className="runs">
            <Runbox label="Virtual threads active" value={formatValue(panel(reliability, 'jvm_threads')?.value, panel(reliability, 'jvm_threads')?.unit)} />
            <Runbox label="Bulkhead exec / queued" value={bulkheadValue(reliability)} />
            <Runbox label="Open breakers" value={formatValue(panel(reliability, 'breakers_open')?.value, panel(reliability, 'breakers_open')?.unit)} />
            <Runbox label="Error rate" value={formatValue(panel(reliability, 'error_rate')?.value, panel(reliability, 'error_rate')?.unit)} />
          </div>
          <BreakerList rows={breakerRows} />
        </PanelShell>
        <PanelShell className="col5" title="Latency distribution" badge="↗ Grafana" caption="Agent latency distribution · current range">
          <Histogram rows={latency} />
        </PanelShell>
        <PanelShell className="col6" title="Agent selection" badge="↗ Grafana" caption="Which agents the router chose">
          <Bars rows={selection} empty="No agent-selection samples for this range." />
        </PanelShell>
        <PanelShell className="col6" title="Intent mix" caption="chat_intent_total · by type">
          <Bars rows={intent} empty="No intent-mix samples for this range." />
        </PanelShell>
      </div>
    </>
  )
}

function OrchestrationView({ boards }: { boards: Record<number, Board> }) {
  const orchestration = boards[8]
  const degradation = panel(orchestration, 'degradation_rate')
  const followupShare = panel(orchestration, 'followup_share')
  const dagPlans = panel(orchestration, 'dag_plans')
  const intentMix = labeledRows(panel(orchestration, 'intent_mix')?.rows)
  const outcomeTaxonomy = labeledRows(panel(orchestration, 'outcome_taxonomy')?.rows)
  const fanoutShape = labeledRows(panel(orchestration, 'fanout_shape')?.rows)

  return (
    <>
      <SystemNotice
        tone="nom"
        tag="Decision intelligence"
        message="How the gateway routes every question — one plain-English ask fans out across specialist agents, and every step here is a metric it already emits."
      />
      <div className="strip">
        <KpiCard label="Follow-up share" panel={followupShare} detail="multi-turn conversations" />
        <KpiCard label="Graceful degradation" panel={degradation} detail="answered from a partial fan-out" />
        <KpiCard label="Multi-step plans" panel={dagPlans} detail="composable DAG orchestration" />
      </div>
      <div className="grid grid-gap-top">
        <PanelShell className="col6" title="Intent mix" caption="chat_intent_total — how questions are classified">
          <Bars rows={intentMix} empty="No intent-classification samples for this range." />
        </PanelShell>
        <PanelShell className="col6" title="Outcome taxonomy" caption="conduit_request_outcome_total — how every request resolved">
          <Bars rows={outcomeTaxonomy} empty="No request-outcome samples for this range." />
        </PanelShell>
        <PanelShell className="col12" title="Fan-out shape" caption="conduit_fanout_duration_seconds_count — agents dispatched per question (1 vs 2 vs 3+)">
          <Bars rows={fanoutShape} empty="No fan-out samples for this range." />
        </PanelShell>
      </div>
    </>
  )
}

function EconomicsView({ boards, cost, range }: { boards: Record<number, Board>; cost: CostSummary | null; range: RangeKey }) {
  const costBoard = boards[7]
  const bySegment = cost?.bySegment ?? []
  const byModel = cost?.byModel ?? []
  const byUser = cost?.byUser ?? []
  const costTrend = panel(costBoard, 'cost_by_day')?.series ?? []

  return (
    <>
      <SystemNotice
        tone="nom"
        tag="Cost verdict"
        message={
          cost
            ? `${currency(cost.unitEconomics.costPerQuestionUsd)} / question · ${compactNumber(cost.totalTokens)} tokens over ${compactNumber(cost.questions)} questions.`
            : 'No cost data for this range.'
        }
      />
      <div className="grid">
        <PanelShell className="col7" title="Cost by segment" badge="Live" caption={cost ? `Total ${currency(cost.totalCostUsd)} · ${currency(cost.unitEconomics.costPerQuestionUsd)} / question · ${compactNumber(cost.unitEconomics.tokensPerQuestion)} tokens / question` : 'Cost summary endpoint'}>
          <CostBars rows={bySegment} empty="No cost-by-segment data yet." />
        </PanelShell>
        <PanelShell className="col5" title="Cost over time" badge="Live" caption={`Daily model spend · ${range}`}>
          <AreaChart points={costTrend} color="gold" height={120} />
          <div className="pcts">
            <div>
              total cost<b>{cost ? currency(cost.totalCostUsd) : collecting()}</b>
            </div>
            <div>
              tokens<b>{cost ? compactNumber(cost.totalTokens) : collecting()}</b>
            </div>
            <div>
              $/question<b>{cost ? currency(cost.unitEconomics.costPerQuestionUsd) : collecting()}</b>
            </div>
          </div>
        </PanelShell>
        <PanelShell className="col6" title="Cost by model" caption="Config-driven pricing · per-model breakdown">
          <CostBars rows={byModel} empty="No model spend has been reported." />
        </PanelShell>
        <PanelShell className="col6" title="Top cost by user" caption="Resolved via lookup — not stored in telemetry">
          <CostBars rows={byUser} empty="No user spend has been reported." />
        </PanelShell>
      </div>
    </>
  )
}

function AnswerQualityView({
  boards,
  cost,
  onNavigate,
}: {
  boards: Record<number, Board>
  cost: CostSummary | null
  onNavigate: (view: ViewId) => void
}) {
  const quality = boards[7]
  const scores = labeledRows(panel(quality, 'eval_scores')?.rows)
  const groundingDistribution = labeledRows(panel(quality, 'grounding_distribution')?.rows)
  const groundingByModel = labeledRows(panel(quality, 'grounding_by_model')?.rows)
  const grounding = scoreFor(scores, 'grounding')
  const safety = scoreFor(scores, 'safety')
  const relevance = scoreFor(scores, 'relevance')
  const honesty = scoreFor(scores, 'honesty') ?? scoreFor(scores, 'partial-honesty')
  const gauges = [
    { label: 'Grounding', value: grounding },
    { label: 'Safety', value: safety },
    { label: 'Relevance', value: relevance },
    { label: 'Honesty', value: honesty },
  ]

  return (
    <>
      <SystemNotice
        tone="nom"
        tag="Quality verdict"
        message={
          scores.length > 0
            ? `Independent continuous scores are live across ${compactNumber(cost?.questions ?? 0)} questions.`
            : 'No answer-quality scores for this range.'
        }
      />
      <div className="grid">
        <PanelShell
          className="col7"
          title="Continuous answer quality"
          caption="Every production answer sampled + scored by an independent LLM judge — the model does not grade itself"
          badge="✓ Build certified · CI"
        >
          <div className="gauges four">
            {gauges.map((gauge) => (
              <GaugeDial key={gauge.label} label={gauge.label} value={gauge.value} />
            ))}
          </div>
        </PanelShell>
        <PanelShell className="col5" title="Grounding distribution" badge="◐ Near real-time" caption="Most answers cluster high — the tail is what to fix">
          <ScoreHistogram rows={groundingDistribution} empty="Grounding scores are still collecting." />
        </PanelShell>
        <PanelShell className="col7" title="Needs review — low-scoring answers" badge="↗ Langfuse" caption="Click any to replay the decision and see why it scored low">
          <button type="button" className="out empty-out" onClick={() => onNavigate('iv')}>
            <span className="sc mid">--</span>
            <span className="oq">
              <span className="t">Low-scoring answer outliers are collecting from Langfuse.</span>
            </span>
            <span className="arw">replay →</span>
          </button>
        </PanelShell>
        <PanelShell className="col5" title="Grounding by model" caption="Is one model less trustworthy? — a real procurement question">
          <Bars rows={groundingByModel} unit="score" empty="No model-level grounding scores yet." />
        </PanelShell>
      </div>
    </>
  )
}

function UserPicker({
  onSelect,
  selected,
  users,
}: {
  onSelect: (label: string) => void
  selected: CostSlice | undefined
  users: CostSlice[]
}) {
  return (
    <div className="upick">
      {users.length > 0 ? (
        users.slice(0, 8).map((user) => (
          <button
            key={user.label}
            type="button"
            className={clsx('uchip', user.label === selected?.label && 'on')}
            onClick={() => onSelect(user.label)}
          >
            <span className="av">{initialsFor(user.label)}</span>
            {formatLabel(user.label)}
          </button>
        ))
      ) : (
        <span className="uchip on">User spend is collecting</span>
      )}
    </div>
  )
}

function UserIdentityCard({
  rank,
  selected,
  shareOfSpend,
  spendVsTop,
  total,
}: {
  rank?: number
  selected: CostSlice | undefined
  shareOfSpend?: number | null
  spendVsTop?: number | null
  total?: number
}) {
  const showRank = Boolean(selected) && rank !== undefined && rank > 0 && total !== undefined && total > 0

  return (
    <div className="card user-card">
      <div className="user-head">
        <span>{initialsFor(selected?.label ?? 'IA')}</span>
        {selected ? formatLabel(selected.label) : 'No user selected'}
        {showRank ? <span className="rank-badge">#{rank} of {total} by spend</span> : null}
      </div>
      <div className="user-meta">
        {selected && shareOfSpend !== undefined && shareOfSpend !== null
          ? `${percent(shareOfSpend)} of desk spend`
          : 'Per-principal analytics from live cost telemetry.'}
      </div>
      {selected && spendVsTop !== undefined && spendVsTop !== null ? (
        <div className="bar user-bar">
          <span className="bl">vs top spender</span>
          <div className="track">
            <div className="fill" style={{ width: `${Math.max(4, Math.min(100, spendVsTop * 100))}%` }} />
          </div>
          <span className="bv">{percent(spendVsTop)}</span>
        </div>
      ) : null}
    </div>
  )
}

function ByUserSpendQualityView({
  boards,
  cost,
  onSelectUser,
  selectedLabel,
}: {
  boards: Record<number, Board>
  cost: CostSummary | null
  onSelectUser: (label: string) => void
  selectedLabel: string | null
}) {
  const users = cost?.byUser ?? []
  const selected = users.find((user) => user.label === selectedLabel) ?? users[0]
  const qualityScores = labeledRows(panel(boards[7], 'eval_scores')?.rows)

  // Honest per-user derivations — computed from the already-fetched cost slice + totals.
  const totalCostUsd = cost?.totalCostUsd ?? 0
  const shareOfSpend = selected && totalCostUsd > 0 ? selected.costUsd / totalCostUsd : null
  const rankedBySpend = [...users].sort((a, b) => b.costUsd - a.costUsd)
  const rank = selected ? rankedBySpend.findIndex((user) => user.label === selected.label) + 1 : 0
  const maxUserCostUsd = users.reduce((max, user) => Math.max(max, user.costUsd), 0)
  const spendVsTop = selected && maxUserCostUsd > 0 ? selected.costUsd / maxUserCostUsd : null
  const costPerQuestion = selected && selected.count > 0 ? selected.costUsd / selected.count : null
  const tokensPerQuestion = selected && selected.count > 0 ? selected.tokens / selected.count : null

  return (
    <>
      <UserPicker users={users} selected={selected} onSelect={onSelectUser} />
      <div className="strip user-strip spend-strip">
        <UserIdentityCard
          selected={selected}
          rank={rank}
          total={users.length}
          shareOfSpend={shareOfSpend}
          spendVsTop={spendVsTop}
        />
        <KpiCard
          label="Cost"
          value={selected ? currency(selected.costUsd) : collecting()}
          detail={shareOfSpend !== null ? `${percent(shareOfSpend)} of desk spend` : 'current range'}
        />
        <KpiCard
          label="$ / question"
          value={costPerQuestion !== null ? currency(costPerQuestion) : collecting()}
          detail={tokensPerQuestion !== null ? `${compactNumber(tokensPerQuestion)} tokens / question` : 'this user'}
        />
        <KpiCard label="Questions" value={selected ? compactNumber(selected.count) : collecting()} detail="reported requests" />
        <KpiCard label="Avg grounding" value={formatScore(scoreFor(qualityScores, 'grounding'))} detail={qualityScores.length ? 'global score only' : 'no user endpoint'} />
      </div>
      <div className="grid">
        <PanelShell className="col12" title="Continuous eval — this user" badge="◐ Near real-time" caption="Every answer sampled + scored by the independent evaluator">
          {qualityScores.length > 0 ? (
            <div className="scores">
              {qualityScores.slice(0, 5).map((score) => (
                <span className="chip" key={score.label}>
                  {formatLabel(score.label)} <b>{formatScore(score.value)}</b>
                </span>
              ))}
            </div>
          ) : (
            <EmptyState>No user-level eval scores for this range.</EmptyState>
          )}
        </PanelShell>
      </div>
    </>
  )
}

function ByUserAuditView({
  boards,
  cost,
  onNavigate,
  onLoadTrace,
  onSelectUser,
  selectedLabel,
}: {
  boards: Record<number, Board>
  cost: CostSummary | null
  onNavigate: (view: ViewId) => void
  onLoadTrace: (conversationId: string) => void
  onSelectUser: (label: string) => void
  selectedLabel: string | null
}) {
  const users = cost?.byUser ?? []
  const selected = users.find((user) => user.label === selectedLabel) ?? users[0]
  const compaction = tableRows(panel(boards[7], 'compaction')?.rows)[0] ?? null
  const ledgerRows = tableRows(panel(boards[3], 'authz_ledger')?.rows)
  const attachedPct = compaction ? numericField(compaction, ['attachedPct']) ?? 0 : null
  const compactions = compaction ? numericField(compaction, ['events']) ?? 0 : null

  return (
    <>
      <UserPicker users={users} selected={selected} onSelect={onSelectUser} />
      <div className="strip user-strip">
        <UserIdentityCard selected={selected} />
        <KpiCard label="Conversations" value={selected ? compactNumber(selected.count) : collecting()} detail="requests in range" />
        <KpiCard label="Compactions" value={compactions === null ? collecting() : compactNumber(compactions)} detail="long sessions summarized" />
        <KpiCard label="Summary attached" value={attachedPct === null ? collecting() : percent(attachedPct / 100)} detail="context retention" />
      </div>
      <div className="grid">
        <PanelShell className="col7" title="Authorization ledger — audit trail" badge="Live" caption="Every authorization decision · click to replay the conversation">
          {ledgerRows.length > 0 ? (
            <div className="feed">
              {ledgerRows.slice(0, 8).map((row, index) => (
                <DecisionRow key={rowKey(row, index)} row={row} onClick={() => onNavigate('iv')} onLoadTrace={onLoadTrace} />
              ))}
            </div>
          ) : (
            <EmptyState>No authorization-ledger rows for this range.</EmptyState>
          )}
        </PanelShell>
        <PanelShell className="col5" title="Memory compactions" caption="Long sessions summarized to fit context">
          <CompactionStat row={compaction} />
        </PanelShell>
      </div>
    </>
  )
}

function InvestigateView({
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
  const events = flattenTrace(trace)

  return (
    <>
      <form
        className="lookup"
        onSubmit={(event) => {
          event.preventDefault()
          void onLoadTrace(conversationId)
        }}
      >
        <input
          value={conversationId}
          onChange={(event) => setConversationId(event.target.value)}
          placeholder="conversation id"
          spellCheck={false}
        />
        <button type="submit" disabled={loading}>
          {loading ? 'Replaying...' : 'Replay →'}
        </button>
      </form>
      {error && <SystemNotice tone="danger" tag="Replay failed" message={error} />}
      <div className="p col12">
        <div className="hd">
          <h3>Decision replay — how this answer was produced</h3>
          <span className="badge b-live">{trace ? `${trace.requestCount} requests · gateway-native` : 'gateway-native'}</span>
        </div>
        {events.length > 0 ? (
          <div className="story">
            {events.slice(0, 12).map((event, index) => (
              <TraceStep key={`${event.requestId}-${index}`} event={event} firstTimestamp={events[0]?.timestamp} />
            ))}
          </div>
        ) : (
          <EmptyState>Enter a conversation id to replay gateway-native trace events.</EmptyState>
        )}
        <div className="hd replay-heading">
          <h3>Span timeline — precise timing</h3>
          <span className="ext">↗ Langfuse trace</span>
        </div>
        {events.length > 0 ? <Waterfall events={events} /> : <EmptyState>Span timing appears after a trace is loaded.</EmptyState>}
        <div className="note">Content shown at your data classification (highest). Narrative for humans · span timeline for precision · both from the same gateway-native trace.</div>
      </div>
    </>
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
        <span>Operations plane</span>
      </div>
    </div>
  )
}

function KpiCard({ detail, label, panel: sourcePanel, value }: { detail?: string; label: string; panel?: Panel; value?: string }) {
  return (
    <div className="card kpi">
      <div className="l">{label}</div>
      <div className="v">{value ?? formatValue(sourcePanel?.value, sourcePanel?.unit)}</div>
      <div className={clsx('d', deltaClass(sourcePanel?.delta))}>{detail ?? deltaText(sourcePanel?.delta)}</div>
    </div>
  )
}

function PanelShell({
  badge,
  caption,
  children,
  className,
  title,
}: {
  badge?: string
  caption?: string
  children: ReactNode
  className: string
  title: string
}) {
  return (
    <div className={clsx('p', className)}>
      <div className="hd">
        <h3>{title}</h3>
        {badge && <span className={clsx('badge', badge.includes('Live') ? 'b-live' : 'b-near')}>{badge}</span>}
      </div>
      {caption && <div className="cap">{caption}</div>}
      {children}
    </div>
  )
}

function SystemNotice({
  action,
  message,
  onAction,
  tag,
  tone,
}: {
  action?: string
  message: string
  onAction?: () => void
  tag: string
  tone?: 'nom' | 'danger'
}) {
  return (
    <div className={clsx('attn', tone === 'nom' && 'nom', tone === 'danger' && 'danger')}>
      <span className="tag">{tag}</span>
      <span className="msg">{message}</span>
      {action && (
        <button type="button" onClick={onAction}>
          {action}
        </button>
      )}
    </div>
  )
}

function AreaChart({ color = 'blue', height = 170, points }: { color?: 'blue' | 'gold'; height?: number; points: Point[] }) {
  const width = 720
  const chartHeight = height
  const line = linePath(points, width, chartHeight)
  const area = line ? `${line} L${width},${chartHeight} L0,${chartHeight} Z` : ''
  const stroke = color === 'gold' ? '#F0C45A' : '#5B9BF0'
  const fill = color === 'gold' ? 'rgba(240,196,90,.16)' : 'rgba(91,155,240,.14)'

  if (!line) return <EmptyState>No series samples for this range.</EmptyState>

  return (
    <svg viewBox={`0 0 ${width} ${chartHeight}`} preserveAspectRatio="none" height={chartHeight}>
      <line className="gline" x1="0" y1={chartHeight * 0.25} x2={width} y2={chartHeight * 0.25} />
      <line className="gline" x1="0" y1={chartHeight * 0.5} x2={width} y2={chartHeight * 0.5} />
      <line className="gline" x1="0" y1={chartHeight * 0.75} x2={width} y2={chartHeight * 0.75} />
      <path d={area} fill={fill} />
      <path d={line} fill="none" stroke={stroke} strokeWidth="2.2" />
      <circle cx={width} cy={lastY(points, chartHeight)} r="4" fill={stroke} />
    </svg>
  )
}

function Donut({ rows }: { rows: LabeledValue[] }) {
  const total = rows.reduce((sum, row) => sum + row.value, 0)
  const colors = ['#3DD68C', '#5B9BF0', '#F0655A', '#F5B14C']
  let offset = 25

  if (!rows.length || total <= 0) return <EmptyState>No outcome mix samples yet.</EmptyState>

  return (
    <div className="donut">
      <svg width="112" height="112" viewBox="0 0 42 42">
        <circle cx="21" cy="21" r="15.9" fill="none" stroke="#182339" strokeWidth="6" />
        {rows.slice(0, 4).map((row, index) => {
          const pct = (row.value / total) * 100
          const dashOffset = offset
          offset -= pct
          return (
            <circle
              key={row.label}
              cx="21"
              cy="21"
              r="15.9"
              fill="none"
              stroke={colors[index]}
              strokeWidth="6"
              strokeDasharray={`${pct} ${100 - pct}`}
              strokeDashoffset={dashOffset}
              transform="rotate(-90 21 21)"
            />
          )
        })}
      </svg>
      <div className="leg">
        {rows.slice(0, 4).map((row, index) => (
          <div key={row.label}>
            <span className="sw" style={{ background: colors[index] }} />
            {formatLabel(row.label)} · {percent(row.value / total)}
          </div>
        ))}
      </div>
    </div>
  )
}

function Bars({
  asPercent,
  empty,
  rows,
  unit,
}: {
  asPercent?: boolean
  empty: string
  rows: LabeledValue[]
  unit?: string
}) {
  const max = Math.max(...rows.map((row) => row.value), 0)
  if (!rows.length || max <= 0) return <EmptyState>{empty}</EmptyState>

  return (
    <>
      {rows.slice(0, 8).map((row) => (
        <BarRow
          key={row.label}
          label={formatLabel(row.label)}
          value={asPercent ? percent(row.value / max) : formatValue(row.value, unit)}
          width={(row.value / max) * 100}
        />
      ))}
    </>
  )
}

function CostBars({ empty, rows }: { empty: string; rows: CostSlice[] }) {
  const max = Math.max(...rows.map((row) => row.costUsd), 0)
  if (!rows.length || max <= 0) return <EmptyState>{empty}</EmptyState>

  return (
    <>
      {rows.slice(0, 8).map((row) => (
        <BarRow key={row.label} label={formatLabel(row.label)} value={currency(row.costUsd)} width={(row.costUsd / max) * 100} />
      ))}
    </>
  )
}

function BarRow({ label, value, width }: { label: string; value: string; width: number }) {
  return (
    <div className="bar">
      <span className="bl" title={label}>{label}</span>
      <div className="track">
        <div className="fill" style={{ width: `${Math.max(4, Math.min(100, width))}%` }} />
      </div>
      <span className="bv">{value}</span>
    </div>
  )
}

function Histogram({ rows }: { rows: LabeledValue[] }) {
  const values = rows.map((row) => row.value).filter((value) => value > 0)
  const max = Math.max(...values, 0)
  if (!values.length || max <= 0) return <EmptyState>No latency distribution samples yet.</EmptyState>

  return (
    <>
      <div className="hist">
        {values.slice(0, 12).map((value, index) => (
          <div key={`${value}-${index}`} className="hb" style={{ height: `${Math.max(5, (value / max) * 100)}%` }} />
        ))}
      </div>
      <div className="pcts">
        <div>
          min<b>{formatValue(Math.min(...values), 'ms')}</b>
        </div>
        <div>
          max<b>{formatValue(max, 'ms')}</b>
        </div>
        <div>
          samples<b>{compactNumber(values.length)}</b>
        </div>
      </div>
    </>
  )
}

function ScoreHistogram({ empty, rows }: { empty: string; rows: LabeledValue[] }) {
  const max = Math.max(...rows.map((row) => row.value), 0)
  const total = rows.reduce((sum, row) => sum + row.value, 0)
  if (!rows.length || total <= 0) return <EmptyState>{empty}</EmptyState>

  return (
    <>
      <div className="hist">
        {rows.map((row) => (
          <div
            key={row.label}
            className="hb"
            title={`${row.label}: ${compactNumber(row.value)}`}
            style={{ height: `${row.value > 0 ? Math.max(6, (row.value / max) * 100) : 2}%` }}
          />
        ))}
      </div>
      <div className="hist-axis">
        <span>0.0</span>
        <span>grounding score</span>
        <span>1.0</span>
      </div>
      <div className="pcts">
        <div>
          samples<b>{compactNumber(total)}</b>
        </div>
        <div>
          top bin<b>{formatLabel(topRow(rows)?.label ?? '')}</b>
        </div>
        <div>
          bins<b>{compactNumber(rows.length)}</b>
        </div>
      </div>
    </>
  )
}

function CompactionStat({ row }: { row: TableRecord | null }) {
  if (!row) return <EmptyState>Memory compaction telemetry is still collecting.</EmptyState>
  const events = numericField(row, ['events']) ?? 0
  const attachedPct = numericField(row, ['attachedPct']) ?? 0
  const tokensSaved = numericField(row, ['tokensSaved']) ?? 0
  const avgMessages = numericField(row, ['avgMessages']) ?? 0

  return (
    <>
      <div className="bar">
        <span className="bl">Summary attached</span>
        <div className="track">
          <div className="fill" style={{ width: `${Math.max(2, Math.min(100, attachedPct))}%` }} />
        </div>
        <span className="bv">{percent(attachedPct / 100)}</span>
      </div>
      <div className="pcts">
        <div>
          compactions<b>{compactNumber(events)}</b>
        </div>
        <div>
          tokens saved<b>{compactNumber(tokensSaved)}</b>
        </div>
        <div>
          avg messages<b>{compactNumber(avgMessages)}</b>
        </div>
      </div>
    </>
  )
}

function GaugeDial({ label, value }: { label: string; value: number | null }) {
  const score = value ?? 0
  const stroke = score >= 0.9 ? '#3DD68C' : score >= 0.75 ? '#F5B14C' : '#F0655A'
  const pct = Math.max(0, Math.min(1, score)) * 100

  return (
    <div className="gauge">
      <div className="ring">
        <svg width="80" height="80" viewBox="0 0 42 42">
          <circle cx="21" cy="21" r="15.9" fill="none" stroke="#182339" strokeWidth="5" />
          <circle
            cx="21"
            cy="21"
            r="15.9"
            fill="none"
            stroke={stroke}
            strokeWidth="5"
            strokeDasharray={`${pct} ${100 - pct}`}
            strokeDashoffset="25"
            transform="rotate(-90 21 21)"
          />
        </svg>
      </div>
      <div className="gv">{formatScore(value)}</div>
      <div className="gl">{label}</div>
    </div>
  )
}

function RunboxGrid({ rows }: { rows: LabeledValue[] }) {
  return (
    <div className="runbox-grid">
      {rows.slice(0, 6).map((row) => (
        <Runbox key={row.label} label={formatLabel(row.label)} value={compactNumber(row.value)} />
      ))}
    </div>
  )
}

function Runbox({ label, value }: { label: string; value: string }) {
  return (
    <div className="runbox">
      <div className="rl">{label}</div>
      <div className="rv">{value}</div>
    </div>
  )
}

function DecisionRow({ onClick, onLoadTrace, row }: { onClick?: () => void; onLoadTrace?: (conversationId: string) => void; row: TableRecord }) {
  const principal = stringField(row, ['principal', 'user', 'username', 'subject', 'actor']) ?? 'principal'
  const status = stringField(row, ['decision', 'outcome', 'status', 'result']) ?? 'recorded'
  const resource = stringField(row, ['resource', 'resourceId', 'resource_type', 'domain', 'agent']) ?? summarizeRow(row)
  const traceId = stringField(row, ['conversationId', 'requestId', 'id'])
  const clickable = Boolean((traceId && onLoadTrace) || onClick)

  function handleClick() {
    if (traceId && onLoadTrace) onLoadTrace(traceId)
    else onClick?.()
  }

  return (
    <button type="button" className={clsx('row', clickable && 'clk')} onClick={handleClick}>
      <span className="av">{initialsFor(principal)}</span>
      <span className="q">
        <b>{formatLabel(principal)}</b> · <span className="t">{resource}</span>
      </span>
      <span className={clsx('st', /deny|fail|forbid/i.test(status) ? 'dn' : /clar/i.test(status) ? 'cl' : 'ok')}>{formatLabel(status)}</span>
      <span className="ms">{stringField(row, ['durationMs', 'latencyMs', 'elapsedMs']) ?? ''}</span>
    </button>
  )
}

function AgentFleetRow({ row }: { row: TableRecord }) {
  const agent = stringField(row, ['agent', 'name', 'label', 'service']) ?? summarizeRow(row)
  const protocol = stringField(row, ['protocol', 'transport']) ?? 'gateway'
  const calls = stringField(row, ['calls', 'count', 'requests']) ?? ''
  const success = stringField(row, ['successRate', 'success', 'success_rate', 'okRate']) ?? ''
  const p95 = stringField(row, ['p95Ms', 'latencyMs', 'avgMs', 'durationMs']) ?? ''

  return (
    <div className="row">
      <div className="q">
        <b>{formatLabel(agent)}</b>
        <br />
        <span className="t">
          {formatLabel(protocol)}
          {calls ? ` · ${calls} calls` : ''}
        </span>
      </div>
      {success && <span className="st ok">{success}</span>}
      <span className="ms">{p95 ? formatMaybeMs(p95) : ''}</span>
    </div>
  )
}

function BreakerList({ rows }: { rows: TableRecord[] }) {
  if (!rows.length) return <EmptyState>No breaker-state rows for this range.</EmptyState>

  return (
    <div className="breaker-list">
      {rows.slice(0, 8).map((row, index) => {
        const name = stringField(row, ['name', 'agent', 'service', 'label']) ?? `breaker ${index + 1}`
        const state = stringField(row, ['state', 'status', 'value']) ?? 'unknown'
        const open = /open|half/i.test(state)
        return (
          <div className="brk" key={rowKey(row, index)}>
            <span>
              <span className="cl" style={{ background: open ? 'var(--amber)' : 'var(--green)' }} />
              {formatLabel(name)}
            </span>
            <span style={{ color: open ? 'var(--amber)' : 'var(--green)' }}>{formatLabel(state)}</span>
          </div>
        )
      })}
    </div>
  )
}

function TraceStep({ event, firstTimestamp }: { event: TraceEventRecord; firstTimestamp?: unknown }) {
  const type = stringValue(event.type) ?? 'trace_event'
  const data = event.data && typeof event.data === 'object' ? (event.data as TableRecord) : {}
  const summary = stringField(data, ['message', 'question', 'intent', 'decision', 'agent', 'outcome', 'status']) ?? summarizeRow(data)
  const timestamp = numericValue(event.timestamp)
  const first = numericValue(firstTimestamp)
  const elapsed = timestamp !== null && first !== null ? timestamp - first : null

  return (
    <div className={clsx('step', /complete|allow|passed|ok/i.test(type + summary) && 'ok', /denied|deny|error|fail/i.test(type + summary) && 'deny')}>
      <div className="node">{nodeFor(type)}</div>
      <div className="txt">
        <div className="s">
          <b>{formatLabel(type)}</b>
          {summary ? ` — ${summary}` : ''}
        </div>
        <div className="meta">request {stringValue(event.requestId) ?? 'unknown'}</div>
      </div>
      <div className="time">{elapsed === 0 ? 't0' : elapsed !== null ? `+${formatDuration(elapsed)}` : ''}</div>
    </div>
  )
}

function Waterfall({ events }: { events: TraceEventRecord[] }) {
  const timestamps = events.map((event) => numericValue(event.timestamp)).filter((value): value is number => value !== null)
  const min = Math.min(...timestamps)
  const max = Math.max(...timestamps)
  const span = Math.max(max - min, 1)

  return (
    <div className="wf">
      {events.slice(0, 10).map((event, index) => {
        const timestamp = numericValue(event.timestamp) ?? min
        const left = ((timestamp - min) / span) * 86
        const type = stringValue(event.type) ?? 'trace_event'
        return (
          <div className="wfr" key={`${stringValue(event.requestId)}-${index}`}>
            <span className="wl">{formatLabel(type)}</span>
            <div className="wtrack">
              <div className={clsx('wbar', /agent/i.test(type) && 'a')} style={{ left: `${left}%`, width: '8%' }} />
            </div>
            <span className="wt">+{formatDuration(timestamp - min)}</span>
          </div>
        )
      })}
    </div>
  )
}

function EmptyState({ children }: { children: ReactNode }) {
  return <div className="empty-state">{children}</div>
}

function signOutLocally() {
  clearSession()
  window.location.assign('/')
}

function panel(board: Board | undefined, id: string): Panel | undefined {
  return board?.panels.find((candidate) => candidate.id === id)
}

function labeledRows(rows: Panel['rows'] | undefined): LabeledValue[] {
  if (!rows) return []
  return rows.flatMap((row) => {
    if (!row || typeof row !== 'object') return []
    const record = row as TableRecord
    const label = stringField(record, ['label', 'name', 'agent', 'model', 'segment', 'type', 'resource', 'status'])
    const value = numericField(record, ['value', 'count', 'costUsd', 'tokens', 'latencyMs', 'avgMs', 'p95Ms', 'score'])
    if (!label || value === null) return []
    return [{ label, value }]
  })
}

function tableRows(rows: Panel['rows'] | undefined): TableRecord[] {
  if (!rows) return []
  return rows.filter((row): row is TableRecord => Boolean(row && typeof row === 'object')).map((row) => row as TableRecord)
}

function topRow(rows: LabeledValue[]): LabeledValue | null {
  return rows.reduce<LabeledValue | null>((top, row) => (!top || row.value > top.value ? row : top), null)
}

function scoreFor(rows: LabeledValue[], target: string): number | null {
  const match = rows.find((row) => row.label.toLowerCase().replace(/\s+/g, '-').includes(target))
  return match?.value ?? null
}

function numberValue(sourcePanel: Panel | undefined): number {
  return numericValue(sourcePanel?.value) ?? 0
}

function numericField(row: TableRecord, keys: string[]): number | null {
  for (const key of keys) {
    const value = numericValue(row[key])
    if (value !== null) return value
  }
  return null
}

function stringField(row: TableRecord, keys: string[]): string | null {
  for (const key of keys) {
    const value = stringValue(row[key])
    if (value) return value
  }
  return null
}

function stringValue(value: unknown): string | null {
  if (typeof value === 'string' && value.trim()) return value
  if (typeof value === 'number' && Number.isFinite(value)) return String(value)
  if (typeof value === 'boolean') return value ? 'true' : 'false'
  return null
}

function numericValue(value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string') {
    const parsed = Number(value.replace(/[^0-9.-]/g, ''))
    return Number.isFinite(parsed) ? parsed : null
  }
  return null
}

function summarizeRow(row: TableRecord): string {
  const entries = Object.entries(row)
    .filter(([, value]) => value !== null && value !== undefined && typeof value !== 'object')
    .slice(0, 3)
    .map(([key, value]) => `${formatLabel(key)} ${String(value)}`)
  return entries.join(' · ') || 'live record'
}

function formatValue(value: unknown, unit?: string): string {
  if (value === undefined || value === null) return collecting()
  const number = numericValue(value)
  if (number === null) return String(value)
  if (unit === 'percent' || unit === '%') return percent(number > 1 ? number / 100 : number)
  if (unit === 'score') return formatScore(number)
  if (unit === 'usd') return currency(number)
  if (unit === 'ms') return number >= 1000 ? `${(number / 1000).toFixed(2)}s` : `${Math.round(number)}ms`
  return compactNumber(number)
}

// Bulkhead is a paired gauge (executing / queued). When Grafana reports neither, show one honest
// "Unavailable" rather than "N/A / N/A"; when only one side is present, render it and mark the gap.
function bulkheadValue(reliability: Board | undefined): string {
  const exec = panel(reliability, 'bulkhead_executing')
  const queued = panel(reliability, 'bulkhead_queued')
  if (exec?.value == null && queued?.value == null) return 'Unavailable'
  return `${formatValue(exec?.value, exec?.unit)} / ${formatValue(queued?.value, queued?.unit)}`
}

function formatMaybeMs(value: string): string {
  const number = numericValue(value)
  return number === null ? value : formatValue(number, 'ms')
}

function compactNumber(value: number): string {
  return new Intl.NumberFormat('en-US', { maximumFractionDigits: value < 10 ? 2 : 1, notation: Math.abs(value) >= 10_000 ? 'compact' : 'standard' }).format(value)
}

function currency(value: number): string {
  return new Intl.NumberFormat('en-US', { currency: 'USD', maximumFractionDigits: value < 1 ? 4 : 2, minimumFractionDigits: value < 1 ? 4 : 2, style: 'currency' }).format(value)
}

function percent(value: number): string {
  return new Intl.NumberFormat('en-US', { maximumFractionDigits: 1, style: 'percent' }).format(value)
}

function formatScore(value: number | null | undefined): string {
  return value === null || value === undefined ? collecting() : value.toFixed(2)
}

function formatLabel(value: string): string {
  return value
    .replace(/[_-]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .replace(/\b\w/g, (letter) => letter.toUpperCase())
}

function deltaClass(delta: number | undefined): string {
  if (delta === undefined) return 'flat'
  if (delta > 0) return 'up'
  if (delta < 0) return 'down'
  return 'flat'
}

function deltaText(delta: number | undefined): string {
  if (delta === undefined) return '● live'
  if (delta > 0) return `▲ ${Math.abs(delta).toFixed(1)}%`
  if (delta < 0) return `▼ ${Math.abs(delta).toFixed(1)}%`
  return '● steady'
}

function linePath(points: Point[], width: number, height: number): string {
  if (points.length < 2) return ''
  const values = points.map((point) => point.v)
  const min = Math.min(...values)
  const max = Math.max(...values)
  const spread = Math.max(max - min, 1)
  return points
    .map((point, index) => {
      const x = (index / (points.length - 1)) * width
      const y = height - ((point.v - min) / spread) * (height * 0.72) - height * 0.14
      return `${index === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`
    })
    .join(' ')
}

function lastY(points: Point[], height: number): number {
  const values = points.map((point) => point.v)
  const min = Math.min(...values)
  const max = Math.max(...values)
  const spread = Math.max(max - min, 1)
  const last = points[points.length - 1]?.v ?? 0
  return height - ((last - min) / spread) * (height * 0.72) - height * 0.14
}

function flattenTrace(trace: ConversationTrace | null): TraceEventRecord[] {
  if (!trace) return []
  return trace.requests.flatMap((request) =>
    request.events.map((event) => {
      const record = event && typeof event === 'object' ? (event as TraceEventRecord) : {}
      return { ...record, requestId: record.requestId ?? request.requestId }
    }),
  )
}

function nodeFor(type: string): string {
  if (/intent/i.test(type)) return '✎'
  if (/gate|auth|entitlement|deny/i.test(type)) return '⛨'
  if (/agent/i.test(type)) return '⚙'
  if (/synth|complete/i.test(type)) return '◆'
  return '◎'
}

function formatDuration(ms: number): string {
  if (ms >= 1000) return `${(ms / 1000).toFixed(2)}s`
  return `${Math.max(0, Math.round(ms))}ms`
}

function initialsFor(name: string): string {
  const cleaned = name.replace(/[_-]+/g, ' ').trim()
  return (
    cleaned
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((part) => part[0]?.toUpperCase())
      .join('') || 'IA'
  )
}

function collecting(): string {
  return 'N/A'
}

function rowKey(row: TableRecord, index: number): string {
  return stringField(row, ['id', 'requestId', 'conversationId', 'label', 'name']) ?? `row-${index}`
}

function relativeSeconds(date: Date): number {
  return Math.max(0, Math.round((Date.now() - date.getTime()) / 1000))
}
