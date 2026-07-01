import { NavLink } from 'react-router-dom'
import { LayoutDashboard, Users, Shield, UsersRound, FileText, LogOut, Clock } from 'lucide-react'
import { clsx } from 'clsx'
import { useAuth } from '../hooks/useAuth'

const nav = [
  { to: '/',        label: 'Dashboard', icon: LayoutDashboard },
  { to: '/users',   label: 'Users',     icon: Users },
  { to: '/teams',   label: 'Teams',     icon: UsersRound },
  { to: '/roles',   label: 'Roles',     icon: Shield },
  { to: '/policies',label: 'Policies',  icon: FileText },
  { to: '/audit',   label: 'Audit Log', icon: Clock },
]

export function Sidebar() {
  const { user, logout } = useAuth()

  return (
    <aside className="w-60 shrink-0 bg-white border-r border-slate-200 flex flex-col h-screen sticky top-0">
      {/* Logo */}
      <div className="px-5 py-5 border-b border-slate-200">
        <div className="flex items-center gap-2.5">
          <div className="w-7 h-7 bg-brand-600 rounded-md flex items-center justify-center">
            <span className="text-white font-bold text-sm">M</span>
          </div>
          <div>
            <p className="text-sm font-semibold text-slate-900">Meridian</p>
            <p className="text-[11px] text-slate-500 -mt-0.5">Admin Console</p>
          </div>
        </div>
      </div>

      {/* Nav */}
      <nav className="flex-1 px-3 py-4 space-y-0.5">
        {nav.map(({ to, label, icon: Icon }) => (
          <NavLink
            key={to}
            to={to}
            end={to === '/'}
            className={({ isActive }) =>
              clsx(
                'sidebar-link',
                isActive && 'active',
              )
            }
          >
            <Icon size={16} />
            {label}
          </NavLink>
        ))}
      </nav>

      {/* User footer */}
      <div className="px-3 py-4 border-t border-slate-200">
        <div className="flex items-center gap-3 px-2 py-2">
          <div className="w-7 h-7 rounded-full bg-brand-100 flex items-center justify-center">
            <span className="text-brand-700 text-xs font-semibold">
              {user?.username?.charAt(0).toUpperCase() ?? '?'}
            </span>
          </div>
          <div className="flex-1 min-w-0">
            <p className="text-xs font-medium text-slate-900 truncate">{user?.username}</p>
            <p className="text-[11px] text-slate-500 truncate">{user?.id}</p>
          </div>
          <button
            onClick={logout}
            className="p-1.5 text-slate-400 hover:text-slate-600 hover:bg-slate-100 rounded-md transition-colors"
            title="Sign out"
          >
            <LogOut size={14} />
          </button>
        </div>
      </div>
    </aside>
  )
}
