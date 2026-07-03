import { useState, useRef, useEffect, useCallback } from 'react'
import { NavLink, useNavigate, useParams } from 'react-router-dom'
import { MessageSquare, Plus, Folder, Trash2, Pencil, Archive, Search, LogOut, X } from 'lucide-react'
import clsx from 'clsx'
import { useConversations, useDeleteConversation, useUpdateConversation } from '../hooks/useConversations'
import { useProjects } from '../hooks/useProjects'
import { useUser } from './AuthGate'
import type { Conversation } from '../api/types'

function ConversationItem({
  conv,
  active,
  onDelete,
  onRename,
  onArchive,
}: {
  conv: Conversation
  active: boolean
  onDelete: (id: string) => void
  onRename: (id: string, title: string) => void
  onArchive: (id: string) => void
}) {
  const [hovered, setHovered] = useState(false)
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(conv.title)

  function commitRename() {
    const trimmed = draft.trim()
    if (trimmed && trimmed !== conv.title) {
      onRename(conv.id, trimmed)
    }
    setEditing(false)
  }

  return (
    <div
      className={clsx(
        'group relative flex items-center gap-2 px-3 py-2 rounded-md text-sm font-medium transition-colors cursor-pointer',
        'text-slate-300 hover:bg-white/10 hover:text-white',
        active && 'bg-white/10 text-white'
      )}
      style={active ? { boxShadow: 'inset 3px 0 0 #e8bf4f' } : undefined}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
    >
      <MessageSquare size={14} className={clsx('shrink-0', active && 'text-gold-300')} />

      {editing ? (
        <input
          className="flex-1 bg-axiom-800 text-white text-sm rounded px-1 py-0.5 outline-none focus:ring-1 focus:ring-gold-400 min-w-0"
          value={draft}
          autoFocus
          onChange={(e) => setDraft(e.target.value)}
          onBlur={commitRename}
          onKeyDown={(e) => {
            if (e.key === 'Enter') commitRename()
            if (e.key === 'Escape') setEditing(false)
          }}
        />
      ) : (
        <NavLink
          to={`/c/${conv.id}`}
          className="flex-1 truncate min-w-0"
        >
          {conv.title || 'Untitled'}
        </NavLink>
      )}

      {(hovered || active) && !editing && (
        <div className="flex items-center gap-1 shrink-0">
          <button
            onClick={(e) => { e.preventDefault(); setDraft(conv.title); setEditing(true) }}
            className="p-0.5 rounded text-slate-400 hover:text-white"
            title="Rename"
          >
            <Pencil size={12} />
          </button>
          <button
            onClick={(e) => { e.preventDefault(); onArchive(conv.id) }}
            className="p-0.5 rounded text-slate-400 hover:text-white"
            title="Archive"
          >
            <Archive size={12} />
          </button>
          <button
            onClick={(e) => { e.preventDefault(); onDelete(conv.id) }}
            className="p-0.5 rounded text-slate-400 hover:text-red-400"
            title="Delete"
          >
            <Trash2 size={12} />
          </button>
        </div>
      )}
    </div>
  )
}

export function Sidebar() {
  const { id: activeId } = useParams()
  const navigate = useNavigate()
  const user = useUser()

  const [search, setSearch] = useState('')
  const searchRef = useRef<HTMLInputElement>(null)

  const { data: conversations = [], isLoading } = useConversations()
  const { data: projects = [] } = useProjects()
  const deleteMutation = useDeleteConversation()
  const updateMutation = useUpdateConversation()

  // Keyboard shortcuts
  const handleNewChat = useCallback(() => {
    navigate('/c/new')
  }, [navigate])

  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      const mod = e.metaKey || e.ctrlKey
      // Cmd/Ctrl+N → new chat (when not typing in a text input)
      if (mod && e.key === 'n' && !(e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement)) {
        e.preventDefault()
        handleNewChat()
      }
      // / → focus search (when not typing)
      if (e.key === '/' && !(e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement)) {
        e.preventDefault()
        searchRef.current?.focus()
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [handleNewChat])

  function handleDelete(id: string) {
    deleteMutation.mutate(id, {
      onSuccess: () => {
        if (activeId === id) navigate('/c/new')
      },
    })
  }

  function handleRename(id: string, title: string) {
    updateMutation.mutate({ id, title })
  }

  function handleArchive(id: string) {
    updateMutation.mutate({ id, archived: true }, {
      onSuccess: () => {
        if (activeId === id) navigate('/c/new')
      },
    })
  }

  // Filter conversations by search query (title match, case-insensitive)
  const query = search.trim().toLowerCase()
  const filtered = query
    ? conversations.filter((c) => (c.title || '').toLowerCase().includes(query))
    : conversations

  // Group conversations by project
  const ungrouped = filtered.filter((c) => !c.projectId)
  const grouped = projects
    .map((p) => ({
      project: p,
      convs: filtered.filter((c) => c.projectId === p.id),
    }))
    .filter((g) => g.convs.length > 0)

  return (
    <div className="flex flex-col h-full bg-axiom-950 w-64 shrink-0">
      {/* Brand */}
      <div className="flex items-center gap-3 px-4 py-5 border-b border-white/10">
        <div className="axiom-mark h-8 w-8 text-axiom-950 font-bold text-sm">C</div>
        <div>
          <p className="text-white font-semibold text-sm leading-none">Conduit</p>
          <p className="text-xs text-gold-400 mt-0.5">Enterprise AI Gateway</p>
        </div>
      </div>

      {/* New Chat */}
      <div className="px-3 pt-4 pb-2">
        <button
          onClick={handleNewChat}
          className="flex w-full items-center gap-2 px-3 py-2 rounded-md bg-gold-400 text-axiom-950 text-sm font-semibold hover:bg-gold-300 transition-colors"
          title="New chat (⌘N)"
        >
          <Plus size={16} />
          New Chat
        </button>
      </div>

      {/* Search */}
      <div className="px-3 pb-2">
        <div className="relative flex items-center">
          <Search size={13} className="absolute left-2.5 text-slate-500 pointer-events-none" />
          <input
            ref={searchRef}
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search  (/)"
            className="w-full pl-8 pr-7 py-1.5 bg-axiom-800 text-slate-200 text-xs rounded-md placeholder:text-slate-500 outline-none focus:ring-1 focus:ring-gold-500 transition-colors"
          />
          {search && (
            <button
              onClick={() => setSearch('')}
              className="absolute right-2 text-slate-500 hover:text-slate-300"
              tabIndex={-1}
            >
              <X size={12} />
            </button>
          )}
        </div>
      </div>

      {/* Conversation list */}
      <div className="flex-1 overflow-y-auto px-2 py-2 space-y-0.5">
        {isLoading && (
          <div className="space-y-1 px-1">
            {[1, 2, 3].map((n) => (
              <div key={n} className="h-8 rounded-md bg-axiom-800/60 animate-pulse" />
            ))}
          </div>
        )}

        {!isLoading && ungrouped.map((conv) => (
          <ConversationItem
            key={conv.id}
            conv={conv}
            active={activeId === conv.id}
            onDelete={handleDelete}
            onRename={handleRename}
            onArchive={handleArchive}
          />
        ))}

        {!isLoading && grouped.map(({ project, convs }) => (
          <div key={project.id} className="mt-4">
            <div className="flex items-center gap-2 px-3 py-1 mb-1">
              <Folder size={12} style={{ color: project.color }} className="shrink-0" />
              <span className="text-xs font-semibold text-slate-400 uppercase tracking-wider truncate">
                {project.name}
              </span>
            </div>
            {convs.map((conv) => (
              <ConversationItem
                key={conv.id}
                conv={conv}
                active={activeId === conv.id}
                onDelete={handleDelete}
                onRename={handleRename}
                onArchive={handleArchive}
              />
            ))}
          </div>
        ))}

        {!isLoading && filtered.length === 0 && (
          <p className="text-xs text-slate-500 px-3 py-2">
            {query ? 'No conversations match your search.' : 'No conversations yet.'}
          </p>
        )}
      </div>

      {/* User chip + logout */}
      <div className="border-t border-white/10 px-4 py-3 flex items-center gap-3">
        <div className="h-8 w-8 rounded-full bg-axiom-700 flex items-center justify-center text-white text-xs font-semibold shrink-0">
          {user.username.slice(0, 2).toUpperCase()}
        </div>
        <div className="flex-1 min-w-0">
          <p className="text-sm font-medium text-white truncate">{user.username}</p>
          <p className="text-xs text-slate-400 truncate">{user.email}</p>
        </div>
        <a
          href="/api/auth/logout"
          className="shrink-0 p-1.5 rounded text-slate-500 hover:text-white transition-colors"
          title="Sign out"
        >
          <LogOut size={14} />
        </a>
      </div>
    </div>
  )
}
