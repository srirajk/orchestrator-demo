import { useState } from 'react'
import { NavLink, useNavigate, useParams } from 'react-router-dom'
import { MessageSquare, Plus, Folder, Trash2, Pencil, Check, X, LogOut } from 'lucide-react'
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
}: {
  conv: Conversation
  active: boolean
  onDelete: (id: string) => void
  onRename: (id: string, title: string) => void
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

  const { data: conversations = [] } = useConversations()
  const { data: projects = [] } = useProjects()
  const deleteMutation = useDeleteConversation()
  const renameMutation = useUpdateConversation()

  function handleDelete(id: string) {
    deleteMutation.mutate(id, {
      onSuccess: () => {
        if (activeId === id) navigate('/c/new')
      },
    })
  }

  function handleRename(id: string, title: string) {
    renameMutation.mutate({ id, title })
  }

  // Group conversations by project
  const ungrouped = conversations.filter((c) => !c.projectId)
  const grouped = projects.map((p) => ({
    project: p,
    convs: conversations.filter((c) => c.projectId === p.id),
  })).filter((g) => g.convs.length > 0)

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
          onClick={() => navigate('/c/new')}
          className="flex w-full items-center gap-2 px-3 py-2 rounded-md bg-gold-400 text-axiom-950 text-sm font-semibold hover:bg-gold-300 transition-colors"
        >
          <Plus size={16} />
          New Chat
        </button>
      </div>

      {/* Conversation list */}
      <div className="flex-1 overflow-y-auto px-2 py-2 space-y-0.5">
        {ungrouped.map((conv) => (
          <ConversationItem
            key={conv.id}
            conv={conv}
            active={activeId === conv.id}
            onDelete={handleDelete}
            onRename={handleRename}
          />
        ))}

        {grouped.map(({ project, convs }) => (
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
              />
            ))}
          </div>
        ))}

        {conversations.length === 0 && (
          <p className="text-xs text-slate-500 px-3 py-2">No conversations yet</p>
        )}
      </div>

      {/* User chip */}
      <div className="border-t border-white/10 px-4 py-3 flex items-center gap-3">
        <div className="h-8 w-8 rounded-full bg-axiom-700 flex items-center justify-center text-white text-xs font-semibold shrink-0">
          {user.username.slice(0, 2).toUpperCase()}
        </div>
        <div className="flex-1 min-w-0">
          <p className="text-sm font-medium text-white truncate">{user.username}</p>
          <p className="text-xs text-slate-400 truncate">{user.email}</p>
        </div>
      </div>
    </div>
  )
}
