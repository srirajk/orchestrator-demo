import type { ElementType, ReactNode } from 'react'

interface PanelProps {
  title: string
  icon: ElementType
  children: ReactNode
  action?: ReactNode
}

export function Panel({ title, icon: Icon, children, action }: PanelProps) {
  return (
    <section className="bg-white border border-slate-200 rounded-lg overflow-hidden">
      <div className="px-4 py-3 border-b border-slate-200 flex items-center justify-between gap-3">
        <div className="flex items-center gap-2 min-w-0">
          <Icon size={16} className="text-slate-400 shrink-0" />
          <h2 className="text-sm font-semibold text-slate-800 truncate">{title}</h2>
        </div>
        {action}
      </div>
      {children}
    </section>
  )
}
