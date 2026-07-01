import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { Users, UsersRound, Shield, FileText, Activity, ArrowRight } from 'lucide-react'
import { statsApi, auditApi } from '../api/client'
import { useAuth } from '../hooks/useAuth'
import { Skeleton } from '../components/ui/Skeleton'
import { EmptyState } from '../components/ui/EmptyState'

function StatCard({
  label,
  value,
  icon: Icon,
  color,
  onClick,
}: {
  label: string
  value: number | string
  icon: React.ElementType
  color: string
  onClick?: () => void
}) {
  return (
    <button
      onClick={onClick}
      className="text-left w-full bg-white rounded-xl border border-slate-200 p-5 flex items-center gap-4 hover:border-slate-300 transition-colors group"
    >
      <div className={`w-10 h-10 rounded-lg flex items-center justify-center ${color}`}>
        <Icon size={18} className="text-white" />
      </div>
      <div className="flex-1">
        <p className="text-2xl font-bold text-slate-900">{value}</p>
        <p className="text-sm text-slate-500 group-hover:text-slate-700 transition-colors">{label}</p>
      </div>
      <ArrowRight size={16} className="text-slate-300 group-hover:text-slate-400 transition-colors opacity-0 group-hover:opacity-100" />
    </button>
  )
}

function SkeletonCard() {
  return (
    <div className="bg-white rounded-xl border border-slate-200 p-5 flex items-center gap-4">
      <Skeleton className="w-10 h-10 rounded-lg" />
      <div className="flex-1">
        <Skeleton className="h-7 w-16 mb-2" />
        <Skeleton className="h-4 w-24" />
      </div>
    </div>
  )
}

function formatRelativeTime(dateStr: string): string {
  const date = new Date(dateStr)
  const now = new Date()
  const seconds = Math.floor((now.getTime() - date.getTime()) / 1000)

  if (seconds < 60) return 'just now'
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}m ago`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}h ago`
  const days = Math.floor(hours / 24)
  return `${days}d ago`
}

const ACTION_COLORS: Record<string, string> = {
  create: 'bg-green-100 text-green-700',
  update: 'bg-blue-100 text-blue-700',
  delete: 'bg-red-100 text-red-700',
  assign: 'bg-purple-100 text-purple-700',
  deploy: 'bg-orange-100 text-orange-700',
}

function getActionColor(action: string): string {
  const normalized = action.toLowerCase()
  for (const [key, color] of Object.entries(ACTION_COLORS)) {
    if (normalized.includes(key)) return color
  }
  return 'bg-slate-100 text-slate-700'
}

export function Dashboard() {
  const navigate = useNavigate()
  const { user } = useAuth()
  const { data: stats, isLoading: statsLoading } = useQuery({
    queryKey: ['stats'],
    queryFn: statsApi.get,
  })
  const { data: auditData, isLoading: auditLoading } = useQuery({
    queryKey: ['audit', 0],
    queryFn: () => auditApi.list(0, 5),
  })

  return (
    <div className="px-8 py-8 max-w-6xl">
      {/* Header */}
      <div className="mb-8">
        <h1 className="text-3xl font-bold text-slate-900">
          Welcome back, {user?.username?.split(' ')[0]}
        </h1>
        <p className="text-sm text-slate-500 mt-2">
          Meridian AI Gateway · Admin Console
        </p>
      </div>

      {/* Stats Grid */}
      <div className="grid grid-cols-1 lg:grid-cols-4 gap-4 mb-8">
        {statsLoading ? (
          <>
            {[...Array(4)].map((_, i) => (
              <SkeletonCard key={i} />
            ))}
          </>
        ) : stats ? (
          <>
            <StatCard
              label="Users"
              value={stats.totalUsers}
              icon={Users}
              color="bg-brand-600"
              onClick={() => navigate('/users')}
            />
            <StatCard
              label="Teams"
              value={stats.totalTeams}
              icon={UsersRound}
              color="bg-violet-600"
              onClick={() => navigate('/teams')}
            />
            <StatCard
              label="Roles"
              value={stats.totalRoles}
              icon={Shield}
              color="bg-emerald-600"
              onClick={() => navigate('/roles')}
            />
            <StatCard
              label="Policies"
              value={stats.totalPolicies}
              icon={FileText}
              color="bg-amber-600"
              onClick={() => navigate('/policies')}
            />
          </>
        ) : null}
      </div>

      {/* Recent Activity Card */}
      <div className="bg-white rounded-xl border border-slate-200 overflow-hidden">
        <div className="px-5 py-4 border-b border-slate-200 flex items-center gap-2">
          <Activity size={16} className="text-slate-400" />
          <h2 className="text-sm font-semibold text-slate-700">Recent Activity</h2>
        </div>

        {auditLoading ? (
          <div className="space-y-0">
            {[...Array(5)].map((_, i) => (
              <div key={i} className="px-5 py-3 border-b border-slate-100 last:border-b-0">
                <div className="space-y-2">
                  <Skeleton className="h-4 w-3/4" />
                  <Skeleton className="h-3 w-1/2" />
                </div>
              </div>
            ))}
          </div>
        ) : auditData?.content && auditData.content.length > 0 ? (
          <>
            <div className="divide-y divide-slate-100">
              {auditData.content.map((entry) => (
                <div key={entry.id} className="px-5 py-3.5 hover:bg-slate-50 transition-colors group">
                  <div className="flex items-center gap-3 mb-1">
                    <div className="w-6 h-6 rounded-full bg-slate-200 flex items-center justify-center text-xs font-semibold text-slate-700 shrink-0">
                      {entry.actorId.charAt(0).toUpperCase()}
                    </div>
                    <div className="flex-1 min-w-0">
                      <p className="text-sm text-slate-900">
                        <span className="font-medium">{entry.actorId}</span>
                        {' '}
                        <span className="text-slate-600">{entry.action}</span>
                        {' '}
                        <span className={`inline-flex px-1.5 py-0.5 rounded text-xs font-medium ${getActionColor(entry.action)}`}>
                          {entry.resourceType}
                        </span>
                      </p>
                      <p className="text-xs text-slate-500 mt-0.5">
                        {entry.resourceId} · {formatRelativeTime(entry.occurredAt)}
                      </p>
                    </div>
                  </div>
                </div>
              ))}
            </div>
            <button
              onClick={() => navigate('/audit')}
              className="w-full px-5 py-3 text-sm font-medium text-brand-600 hover:bg-brand-50 border-t border-slate-200 transition-colors"
            >
              View all activity →
            </button>
          </>
        ) : (
          <div className="px-5 py-10">
            <EmptyState
              icon={<Activity size={32} className="text-slate-300" />}
              title="No activity yet"
              description="Activity will appear here as changes are made"
            />
          </div>
        )}
      </div>
    </div>
  )
}
