import { clsx } from 'clsx'
import type { InputHTMLAttributes, TextareaHTMLAttributes } from 'react'

interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  label?: string
  error?: string
  hint?: string
}

export function Input({ label, error, hint, className, id, ...rest }: InputProps) {
  const inputId = id ?? (label ? label.toLowerCase().replace(/\s+/g, '-') : undefined)
  return (
    <div className="flex flex-col gap-1">
      {label && <label htmlFor={inputId} className="text-sm font-medium text-ink-700">{label}</label>}
      <input
        id={inputId}
        className={clsx(
          'px-3 py-2 text-sm border rounded-md bg-white/95 text-ink-900 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-gold-300 focus:border-axiom-700 transition-colors shadow-sm',
          error ? 'border-red-400 focus:ring-red-300 focus:border-red-500' : 'border-line',
          rest.disabled && 'bg-slate-50 text-slate-500 cursor-not-allowed',
          className,
        )}
        {...rest}
      />
      {error && <p className="text-xs text-red-600">{error}</p>}
      {hint && !error && <p className="text-xs text-ink-500">{hint}</p>}
    </div>
  )
}

interface TextareaProps extends TextareaHTMLAttributes<HTMLTextAreaElement> {
  label?: string
  error?: string
  hint?: string
}

export function Textarea({ label, error, hint, className, ...rest }: TextareaProps) {
  return (
    <div className="flex flex-col gap-1">
      {label && <label className="text-sm font-medium text-ink-700">{label}</label>}
      <textarea
        className={clsx(
          'px-3 py-2 text-sm border rounded-md bg-white/95 text-ink-900 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-gold-300 focus:border-axiom-700 transition-colors resize-none shadow-sm',
          error ? 'border-red-400 focus:ring-red-300 focus:border-red-500' : 'border-line',
          className,
        )}
        {...rest}
      />
      {error && <p className="text-xs text-red-600">{error}</p>}
      {hint && !error && <p className="text-xs text-ink-500">{hint}</p>}
    </div>
  )
}

interface SelectProps extends InputHTMLAttributes<HTMLSelectElement> {
  label?: string
  error?: string
  children: React.ReactNode
}

export function Select({ label, error, className, children, ...rest }: SelectProps) {
  return (
    <div className="flex flex-col gap-1">
      {label && <label className="text-sm font-medium text-ink-700">{label}</label>}
      <select
        className={clsx(
          'px-3 py-2 text-sm border rounded-md bg-white/95 text-ink-900 focus:outline-none focus:ring-2 focus:ring-gold-300 focus:border-axiom-700 transition-colors shadow-sm',
          error ? 'border-red-400' : 'border-line',
          className,
        )}
        {...(rest as React.SelectHTMLAttributes<HTMLSelectElement>)}
      >
        {children}
      </select>
      {error && <p className="text-xs text-red-600">{error}</p>}
    </div>
  )
}
