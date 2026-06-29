import { clsx } from 'clsx'

interface SkeletonProps {
  className?: string
}

export function Skeleton({ className }: SkeletonProps) {
  return (
    <div
      className={clsx(
        'animate-pulse bg-slate-200 rounded',
        className
      )}
      aria-hidden="true"
    />
  )
}
