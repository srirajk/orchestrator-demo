import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Plus, Trash2, Edit2, Users } from 'lucide-react'
import { teamsApi, usersApi, type Team } from '../api/client'
import { Button } from '../components/ui/Button'
import { Input } from '../components/ui/Input'
import { Badge } from '../components/ui/Badge'
import { Dialog } from '../components/ui/Dialog'
import { Skeleton } from '../components/ui/Skeleton'
import { EmptyState } from '../components/ui/EmptyState'
import { useToast } from '../components/ui/Toast'

const EMPTY: Omit<Team, 'memberCount' | 'members'> = {
  id: '',
  name: '',
  description: '',
  defaultRoles: [],
  segments: [],
  allowedDomains: [],
}

function SkeletonCard() {
  return (
    <div className="bg-white rounded-xl border border-slate-200 p-5">
      <div className="flex items-start justify-between mb-3">
        <Skeleton className="w-9 h-9 rounded-lg" />
        <div className="flex gap-1">
          <Skeleton className="w-8 h-8 rounded-md" />
          <Skeleton className="w-8 h-8 rounded-md" />
        </div>
      </div>
      <Skeleton className="h-5 w-32 mb-2" />
      <Skeleton className="h-3 w-48 mb-3" />
      <div className="flex gap-2 mb-3">
        <Skeleton className="h-6 w-16 rounded-full" />
        <Skeleton className="h-6 w-20 rounded-full" />
      </div>
      <Skeleton className="h-4 w-24" />
    </div>
  )
}

export function Teams() {
  const qc = useQueryClient()
  const { toast } = useToast()
  const [open, setOpen] = useState(false)
  const [editing, setEdit] = useState<Team | null>(null)
  const [form, setForm] = useState({ ...EMPTY })
  const [detail, setDetail] = useState<Team | null>(null)
  const [addMember, setAddMember] = useState('')

  const { data: teams = [], isLoading } = useQuery({
    queryKey: ['teams'],
    queryFn: teamsApi.list,
  })
  const { data: users = [] } = useQuery({
    queryKey: ['users'],
    queryFn: usersApi.list,
  })
  const { data: teamDetail } = useQuery({
    queryKey: ['team', detail?.id],
    queryFn: () => teamsApi.get(detail!.id),
    enabled: !!detail,
  })

  const createMut = useMutation({
    mutationFn: () => teamsApi.create(form as Omit<Team, 'memberCount'>),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['teams'] })
      close()
      toast('success', 'Team created')
    },
    onError: (e: Error) => toast('error', e.message),
  })

  const updateMut = useMutation({
    mutationFn: () =>
      teamsApi.update(editing!.id, {
        ...form,
        id: editing!.id,
      } as Omit<Team, 'id' | 'memberCount'>),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['teams'] })
      close()
      toast('success', 'Team updated')
    },
    onError: (e: Error) => toast('error', e.message),
  })

  const deleteMut = useMutation({
    mutationFn: (id: string) => teamsApi.delete(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['teams'] })
      toast('success', 'Team deleted')
    },
    onError: (e: Error) => toast('error', e.message),
  })

  const addMemberMut = useMutation({
    mutationFn: ({ teamId, userId }: { teamId: string; userId: string }) =>
      teamsApi.addMember(teamId, userId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['team', detail?.id] })
      qc.invalidateQueries({ queryKey: ['teams'] })
      setAddMember('')
      toast('success', 'Member added')
    },
    onError: (e: Error) => toast('error', e.message),
  })

  const removeMemberMut = useMutation({
    mutationFn: ({ teamId, userId }: { teamId: string; userId: string }) =>
      teamsApi.removeMember(teamId, userId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['team', detail?.id] })
      qc.invalidateQueries({ queryKey: ['teams'] })
    },
    onError: (e: Error) => toast('error', e.message),
  })

  function openCreate() {
    setEdit(null)
    setForm({ ...EMPTY })
    setOpen(true)
  }
  function openEdit(t: Team) {
    setEdit(t)
    setForm({
      id: t.id,
      name: t.name,
      description: t.description,
      defaultRoles: t.defaultRoles,
      segments: t.segments,
      allowedDomains: t.allowedDomains,
    })
    setOpen(true)
  }
  function close() {
    setOpen(false)
    setEdit(null)
  }

  return (
    <div className="px-8 py-8">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-bold text-slate-900">Teams</h1>
          <p className="text-sm text-slate-500 mt-1">
            {teams.length} team{teams.length !== 1 ? 's' : ''}
          </p>
        </div>
        <Button onClick={openCreate}>
          <Plus size={15} /> New team
        </Button>
      </div>

      {isLoading ? (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {[...Array(6)].map((_, i) => (
            <SkeletonCard key={i} />
          ))}
        </div>
      ) : teams.length === 0 ? (
        <EmptyState
          icon={<Users size={40} className="text-slate-300" />}
          title="No teams yet"
          description="Create a team to group users and assign default roles"
          action={{ label: 'New team', onClick: openCreate }}
        />
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {teams.map(t => (
            <div key={t.id} className="bg-white rounded-xl border border-slate-200 p-5 hover:border-slate-300 transition-colors group">
              <div className="flex items-start justify-between mb-3">
                <div className="w-9 h-9 rounded-lg bg-violet-50 flex items-center justify-center">
                  <Users size={16} className="text-violet-600" />
                </div>
                <div className="flex gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                  <button
                    onClick={() => openEdit(t)}
                    className="p-1.5 text-slate-400 hover:text-slate-700 hover:bg-slate-100 rounded-md transition-colors"
                  >
                    <Edit2 size={13} />
                  </button>
                  <button
                    onClick={() => deleteMut.mutate(t.id)}
                    className="p-1.5 text-slate-400 hover:text-red-600 hover:bg-red-50 rounded-md transition-colors"
                  >
                    <Trash2 size={13} />
                  </button>
                </div>
              </div>
              <h3 className="font-semibold text-slate-900 mb-0.5">{t.name}</h3>
              <p className="text-xs text-slate-500 mb-3 line-clamp-2">
                {t.description || 'No description'}
              </p>
              <div className="flex flex-wrap gap-1 mb-3">
                {(t.segments ?? []).map(s => (
                  <Badge key={s} color="green">
                    {s}
                  </Badge>
                ))}
                {(t.defaultRoles ?? []).map(r => (
                  <Badge key={r} color="blue">
                    {r}
                  </Badge>
                ))}
              </div>
              <div className="flex items-center justify-between">
                <span className="text-xs text-slate-500">
                  {t.memberCount ?? 0} member{(t.memberCount ?? 0) !== 1 ? 's' : ''}
                </span>
                <button
                  onClick={() => setDetail(t)}
                  className="text-xs text-brand-600 hover:underline font-medium"
                >
                  Manage →
                </button>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Create / Edit dialog */}
      <Dialog
        open={open}
        onClose={close}
        title={editing ? `Edit ${editing.name}` : 'New team'}
      >
        <div className="space-y-4">
          {!editing && (
            <Input
              label="Team ID"
              placeholder="wealth-private-banking"
              value={form.id}
              onChange={e => setForm(f => ({ ...f, id: e.target.value }))}
              hint="Lowercase, hyphens only"
              required
            />
          )}
          <Input
            label="Name"
            placeholder="Wealth Private Banking"
            value={form.name}
            onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
            required
          />
          <Input
            label="Description"
            value={form.description}
            onChange={e => setForm(f => ({ ...f, description: e.target.value }))}
          />
          <Input
            label="Segments (comma-separated)"
            placeholder="wealth, servicing"
            value={form.segments.join(', ')}
            onChange={e =>
              setForm(f => ({
                ...f,
                segments: e.target.value
                  .split(',')
                  .map(s => s.trim())
                  .filter(Boolean),
              }))
            }
          />
          <Input
            label="Default roles (comma-separated)"
            placeholder="relationship_manager"
            value={(form.defaultRoles ?? []).join(', ')}
            onChange={e =>
              setForm(f => ({
                ...f,
                defaultRoles: e.target.value
                  .split(',')
                  .map(s => s.trim())
                  .filter(Boolean),
              }))
            }
            hint="New members inherit these roles automatically"
          />
          <Input
            label="Allowed domains (comma-separated)"
            placeholder="wealth-private-banking"
            value={(form.allowedDomains ?? []).join(', ')}
            onChange={e =>
              setForm(f => ({
                ...f,
                allowedDomains: e.target.value
                  .split(',')
                  .map(s => s.trim())
                  .filter(Boolean),
              }))
            }
          />
          <div className="flex gap-2 pt-2 border-t border-slate-100">
            <Button variant="secondary" onClick={close} className="flex-1">
              Cancel
            </Button>
            <Button
              className="flex-1"
              loading={createMut.isPending || updateMut.isPending}
              onClick={() => (editing ? updateMut.mutate() : createMut.mutate())}
            >
              {editing ? 'Save changes' : 'Create team'}
            </Button>
          </div>
        </div>
      </Dialog>

      {/* Team detail / members */}
      <Dialog
        open={!!detail}
        onClose={() => setDetail(null)}
        title={teamDetail?.name ?? detail?.name ?? ''}
        description="Members"
        size="md"
      >
        {teamDetail && (
          <div className="space-y-4">
            <div className="flex gap-2">
              <select
                className="flex-1 px-3 py-2 text-sm border border-slate-300 rounded-md bg-white focus:outline-none focus:ring-2 focus:ring-brand-500"
                value={addMember}
                onChange={e => setAddMember(e.target.value)}
              >
                <option value="">Select a user to add…</option>
                {users
                  .filter(u => !teamDetail.members?.some(m => m.id === u.id))
                  .map(u => (
                    <option key={u.id} value={u.id}>
                      {u.username} ({u.id})
                    </option>
                  ))}
              </select>
              <Button
                size="sm"
                disabled={!addMember}
                loading={addMemberMut.isPending}
                onClick={() =>
                  addMemberMut.mutate({
                    teamId: teamDetail.id,
                    userId: addMember,
                  })
                }
              >
                Add
              </Button>
            </div>

            {teamDetail.members?.length === 0 ? (
              <EmptyState
                icon={<Users size={32} className="text-slate-300" />}
                title="No members yet"
                description="Add team members from the selector above"
              />
            ) : (
              <div className="divide-y divide-slate-100">
                {teamDetail.members?.map(m => (
                  <div key={m.id} className="flex items-center justify-between py-2.5 group">
                    <div className="flex items-center gap-2.5">
                      <div className="w-7 h-7 rounded-full bg-violet-50 flex items-center justify-center">
                        <span className="text-violet-700 text-xs font-semibold">
                          {m.name.charAt(0)}
                        </span>
                      </div>
                      <div>
                        <p className="text-sm font-medium text-slate-900">{m.name}</p>
                        <p className="text-xs text-slate-500">{m.id}</p>
                      </div>
                    </div>
                    <button
                      onClick={() =>
                        removeMemberMut.mutate({
                          teamId: teamDetail.id,
                          userId: m.id,
                        })
                      }
                      className="opacity-0 group-hover:opacity-100 text-xs text-red-500 hover:text-red-700 transition-opacity"
                    >
                      Remove
                    </button>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}
      </Dialog>
    </div>
  )
}
