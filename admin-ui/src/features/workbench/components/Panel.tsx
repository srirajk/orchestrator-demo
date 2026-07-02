import type { ElementType, ReactNode } from 'react'

interface PanelProps {
  title: string
  icon: ElementType
  children: ReactNode
  action?: ReactNode
}

export function Panel({ title, icon: Icon, children, action }: PanelProps) {
  return (
    <section className="surface-panel">
      <div className="px-4 py-3 border-b border-line bg-slate-50/70 flex items-center justify-between gap-3">
        <div className="flex items-center gap-2 min-w-0">
          <Icon size={16} className="text-gold-600 shrink-0" />
          <h2 className="section-heading truncate">{title}</h2>
        </div>
        {action}
      </div>
      {children}
    </section>
  )
}
