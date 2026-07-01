import { clsx } from 'clsx'

type Color = 'blue' | 'green' | 'yellow' | 'red' | 'slate' | 'purple' | 'indigo'

interface Props {
  children: React.ReactNode
  color?: Color
  className?: string
}

const colors: Record<Color, string> = {
  blue:   'bg-blue-50 text-blue-700 ring-1 ring-blue-600/20',
  green:  'bg-green-50 text-green-700 ring-1 ring-green-600/20',
  yellow: 'bg-yellow-50 text-yellow-700 ring-1 ring-yellow-600/20',
  red:    'bg-red-50 text-red-700 ring-1 ring-red-600/20',
  slate:  'bg-slate-100 text-slate-600 ring-1 ring-slate-500/20',
  purple: 'bg-purple-50 text-purple-700 ring-1 ring-purple-600/20',
  indigo: 'bg-indigo-50 text-indigo-700 ring-1 ring-indigo-600/20',
}

export function Badge({ children, color = 'slate', className }: Props) {
  return (
    <span className={clsx('inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium', colors[color], className)}>
      {children}
    </span>
  )
}

export function RoleBadge({ role }: { role: string }) {
  const color: Color =
    role === 'platform_admin' ? 'purple' :
    role === 'domain_admin'   ? 'indigo' :
    role === 'relationship_manager' ? 'blue' : 'slate'
  return <Badge color={color}>{role}</Badge>
}
