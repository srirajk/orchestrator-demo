import { clsx } from 'clsx'

interface StatusPillProps {
  ok: boolean
  label: string
}

export function StatusPill({ ok, label }: StatusPillProps) {
  return (
    <span className={clsx(
      'inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-xs font-medium ring-1',
      ok
        ? 'bg-green-50 text-green-700 ring-green-600/20'
        : 'bg-slate-100 text-slate-600 ring-slate-500/20',
    )}>
      <span className={clsx('h-1.5 w-1.5 rounded-full', ok ? 'bg-green-500' : 'bg-slate-400')} />
      {label}
    </span>
  )
}
