import { useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter'
import { oneDark } from 'react-syntax-highlighter/dist/esm/styles/prism'
import { Copy, Check } from 'lucide-react'
import clsx from 'clsx'
import type { Message as MessageType } from '../api/types'

function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false)

  async function handleCopy() {
    await navigator.clipboard.writeText(text)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  return (
    <button
      onClick={handleCopy}
      className="absolute top-2 right-2 p-1.5 rounded bg-axiom-800/80 text-slate-400 hover:text-white hover:bg-axiom-700 transition-colors"
      title="Copy code"
    >
      {copied ? <Check size={12} /> : <Copy size={12} />}
    </button>
  )
}

interface Props {
  message: MessageType
  isStreaming?: boolean
}

export function Message({ message, isStreaming }: Props) {
  const isUser = message.role === 'user'

  const formattedTime = new Date(message.createdAt).toLocaleTimeString([], {
    hour: '2-digit',
    minute: '2-digit',
  })

  return (
    <div className={clsx('flex gap-3 px-4 py-3', isUser ? 'flex-row-reverse' : 'flex-row')}>
      {/* Avatar */}
      <div
        className={clsx(
          'h-7 w-7 rounded-full shrink-0 flex items-center justify-center text-xs font-semibold mt-0.5',
          isUser ? 'bg-axiom-700 text-white' : 'bg-gold-400 text-axiom-950'
        )}
      >
        {isUser ? 'U' : 'C'}
      </div>

      {/* Bubble */}
      <div className={clsx('max-w-[75%] flex flex-col gap-1', isUser ? 'items-end' : 'items-start')}>
        <div
          className={clsx(
            'rounded-xl px-4 py-3 text-sm leading-relaxed',
            isUser
              ? 'bg-axiom-800 text-white rounded-tr-sm'
              : 'surface-card rounded-tl-sm'
          )}
        >
          {isUser ? (
            <p className="whitespace-pre-wrap">{message.content}</p>
          ) : (
            <ReactMarkdown
              remarkPlugins={[remarkGfm]}
              components={{
                code({ className, children, ...props }) {
                  const match = /language-(\w+)/.exec(className ?? '')
                  const codeStr = String(children).replace(/\n$/, '')
                  if (match) {
                    return (
                      <div className="relative my-2 rounded-lg overflow-hidden">
                        <SyntaxHighlighter
                          style={oneDark}
                          language={match[1]}
                          PreTag="div"
                          customStyle={{ margin: 0, borderRadius: '0.5rem', fontSize: '0.8125rem' }}
                        >
                          {codeStr}
                        </SyntaxHighlighter>
                        <CopyButton text={codeStr} />
                      </div>
                    )
                  }
                  return (
                    <code
                      className="bg-axiom-100 text-axiom-800 rounded px-1 py-0.5 text-xs font-mono"
                      {...props}
                    >
                      {children}
                    </code>
                  )
                },
                p({ children }) {
                  return <p className="mb-2 last:mb-0">{children}</p>
                },
                ul({ children }) {
                  return <ul className="list-disc pl-5 mb-2 space-y-1">{children}</ul>
                },
                ol({ children }) {
                  return <ol className="list-decimal pl-5 mb-2 space-y-1">{children}</ol>
                },
                blockquote({ children }) {
                  return (
                    <blockquote className="border-l-4 border-axiom-300 pl-3 my-2 text-ink-500 italic">
                      {children}
                    </blockquote>
                  )
                },
                h1({ children }) { return <h1 className="text-lg font-bold mb-2 text-ink-900">{children}</h1> },
                h2({ children }) { return <h2 className="text-base font-semibold mb-2 text-ink-900">{children}</h2> },
                h3({ children }) { return <h3 className="text-sm font-semibold mb-1 text-ink-900">{children}</h3> },
                table({ children }) {
                  return (
                    <div className="overflow-x-auto my-2">
                      <table className="min-w-full text-xs border border-line rounded">{children}</table>
                    </div>
                  )
                },
                th({ children }) {
                  return <th className="px-3 py-1.5 bg-axiom-50 font-semibold text-left border-b border-line">{children}</th>
                },
                td({ children }) {
                  return <td className="px-3 py-1.5 border-b border-line">{children}</td>
                },
              }}
            >
              {message.content}
            </ReactMarkdown>
          )}
          {isStreaming && (
            <span className="inline-block w-1.5 h-4 ml-0.5 bg-axiom-500 rounded-sm animate-pulse align-middle" />
          )}
        </div>
        <span className="muted-copy text-xs px-1">{formattedTime}</span>
      </div>
    </div>
  )
}
