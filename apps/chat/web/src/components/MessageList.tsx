import { useEffect, useRef, useState } from 'react'
import { Database, GitBranch, MessageSquare, PenLine, ShieldCheck, Sparkles } from 'lucide-react'
import { Message } from './Message'
import type { Message as MessageType, MessageClientTiming } from '../api/types'
import { pipelineStageForIndex } from '../hooks/useTraceStream'
import type { PipelineStage, PipelineStageKey } from '../hooks/useTraceStream'

const PIPELINE_STAGE_DWELL_MS = 500
const PIPELINE_FADE_MS = 150

const PIPELINE_ICON: Record<PipelineStageKey, typeof Sparkles> = {
  understanding: Sparkles,
  routing: GitBranch,
  access: ShieldCheck,
  gathering: Database,
  composing: PenLine,
}

function LoadingSkeleton() {
  return (
    <div className="flex gap-3 px-4 py-3 animate-pulse">
      <div className="h-7 w-7 rounded-full bg-axiom-100 shrink-0 mt-0.5" />
      <div className="flex-1 space-y-2 max-w-[60%]">
        <div className="h-3 bg-axiom-100 rounded w-3/4" />
        <div className="h-3 bg-axiom-100 rounded w-1/2" />
        <div className="h-3 bg-axiom-100 rounded w-2/3" />
      </div>
    </div>
  )
}

interface Props {
  messages: MessageType[]
  streamingContent: string | null
  streamingTiming?: MessageClientTiming | null
  pipelineStage?: PipelineStage | null
  isLoading?: boolean
}

function useDisplayedPipelineStage(targetStage: PipelineStage | null | undefined) {
  const [displayedStage, setDisplayedStage] = useState<PipelineStage | null>(null)
  const displayedRef = useRef<PipelineStage | null>(null)
  const targetRef = useRef<PipelineStage | null>(null)
  const timerRef = useRef<number | null>(null)
  const lastAdvanceRef = useRef(0)

  function clearAdvanceTimer() {
    if (timerRef.current !== null) {
      window.clearTimeout(timerRef.current)
      timerRef.current = null
    }
  }

  function setDisplayed(stage: PipelineStage | null) {
    displayedRef.current = stage
    setDisplayedStage(stage)
  }

  function scheduleAdvance() {
    if (timerRef.current !== null) return

    const current = displayedRef.current
    const target = targetRef.current
    if (!current || !target || target.index <= current.index) return

    const elapsed = performance.now() - lastAdvanceRef.current
    const delay = Math.max(0, PIPELINE_STAGE_DWELL_MS - elapsed)
    timerRef.current = window.setTimeout(() => {
      timerRef.current = null

      const latestCurrent = displayedRef.current
      const latestTarget = targetRef.current
      if (!latestCurrent || !latestTarget) return

      if (latestTarget.index <= latestCurrent.index) {
        if (latestTarget.index === latestCurrent.index && latestTarget.label !== latestCurrent.label) {
          setDisplayed(latestTarget)
        }
        return
      }

      const nextIndex = latestCurrent.index + 1
      const nextStage = nextIndex === latestTarget.index
        ? latestTarget
        : pipelineStageForIndex(nextIndex, latestTarget.agentCount)
      setDisplayed(nextStage)
      lastAdvanceRef.current = performance.now()
      scheduleAdvance()
    }, delay)
  }

  useEffect(() => {
    targetRef.current = targetStage ?? null

    if (!targetStage) {
      clearAdvanceTimer()
      setDisplayed(null)
      lastAdvanceRef.current = 0
      return
    }

    const current = displayedRef.current
    if (!current) {
      const initialStage = targetStage.index === 0
        ? targetStage
        : pipelineStageForIndex(0, targetStage.agentCount)
      setDisplayed(initialStage)
      lastAdvanceRef.current = performance.now()
      scheduleAdvance()
      return
    }

    if (targetStage.index <= current.index) {
      if (targetStage.index === current.index && targetStage.label !== current.label) {
        setDisplayed(targetStage)
      }
      return
    }

    scheduleAdvance()
  }, [targetStage])

  useEffect(() => () => clearAdvanceTimer(), [])

  return displayedStage
}

function useFadingPipelineStage(stage: PipelineStage | null, streamingContent: string | null) {
  const [renderedStage, setRenderedStage] = useState<PipelineStage | null>(null)
  const [visible, setVisible] = useState(false)
  const renderedRef = useRef<PipelineStage | null>(null)
  const answerHasStarted = streamingContent !== null && streamingContent.length > 0

  useEffect(() => {
    if (stage) {
      renderedRef.current = stage
      setRenderedStage(stage)
      setVisible(true)
      return
    }

    if (answerHasStarted && renderedRef.current) {
      setVisible(false)
      const timer = window.setTimeout(() => {
        renderedRef.current = null
        setRenderedStage(null)
      }, PIPELINE_FADE_MS)
      return () => window.clearTimeout(timer)
    }

    renderedRef.current = null
    setVisible(false)
    setRenderedStage(null)
  }, [stage, answerHasStarted])

  return { stage: renderedStage, visible }
}

function PipelineStatusRow({ stage, visible }: { stage: PipelineStage; visible: boolean }) {
  const Icon = PIPELINE_ICON[stage.key]

  return (
    <div
      className={`flex gap-3 px-4 py-3 transition-opacity duration-150 ${visible ? 'opacity-100' : 'opacity-0'}`}
      aria-live="polite"
      aria-atomic="true"
    >
      <div className="h-7 w-7 rounded-full shrink-0 flex items-center justify-center text-xs font-semibold mt-0.5 bg-gold-400 text-axiom-950">
        C
      </div>
      <div className="max-w-[75%] flex flex-col gap-1 items-start">
        <div className="surface-card rounded-xl rounded-tl-sm px-4 py-3 text-sm leading-relaxed">
          <span className="inline-flex items-center gap-2 text-ink-700">
            <Icon size={16} className="text-indigo-600" aria-hidden="true" />
            <span className="font-medium">{stage.label}</span>
            <span className="inline-flex items-center gap-0.5 text-indigo-500" aria-hidden="true">
              <span className="h-1 w-1 rounded-full bg-current animate-pulse" />
              <span className="h-1 w-1 rounded-full bg-current animate-pulse [animation-delay:120ms]" />
              <span className="h-1 w-1 rounded-full bg-current animate-pulse [animation-delay:240ms]" />
            </span>
          </span>
        </div>
      </div>
    </div>
  )
}

export function MessageList({ messages, streamingContent, streamingTiming, pipelineStage, isLoading }: Props) {
  const bottomRef = useRef<HTMLDivElement>(null)
  const displayedPipelineStage = useDisplayedPipelineStage(pipelineStage)
  const pipelineStatus = useFadingPipelineStage(displayedPipelineStage, streamingContent)

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages.length, streamingContent, pipelineStatus.stage?.index])

  if (isLoading) {
    return (
      <div className="flex-1 overflow-y-auto py-4">
        <LoadingSkeleton />
        <LoadingSkeleton />
        <LoadingSkeleton />
      </div>
    )
  }

  if (messages.length === 0 && streamingContent === null) {
    return (
      <div className="flex-1 flex flex-col items-center justify-center gap-4 text-center px-8">
        <div className="h-14 w-14 rounded-full bg-axiom-100 flex items-center justify-center">
          <MessageSquare size={24} className="text-axiom-400" />
        </div>
        <div>
          <p className="section-heading text-ink-700">Start a conversation</p>
          <p className="muted-copy mt-1">Ask anything across your business domains.</p>
        </div>
      </div>
    )
  }

  return (
    <div className="flex-1 overflow-y-auto py-2">
      {messages.map((msg) => (
        <Message key={msg.id} message={msg} />
      ))}

      {pipelineStatus.stage && (
        <PipelineStatusRow stage={pipelineStatus.stage} visible={pipelineStatus.visible} />
      )}

      {/* Streaming bubble */}
      {streamingContent !== null && streamingContent.length > 0 && (
        <Message
          message={{
            id: '__streaming__',
            role: 'assistant',
            content: streamingContent,
            createdAt: new Date().toISOString(),
            clientTiming: streamingTiming ?? undefined,
          }}
          isStreaming={true}
        />
      )}

      <div ref={bottomRef} />
    </div>
  )
}
