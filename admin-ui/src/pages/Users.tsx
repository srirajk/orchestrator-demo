import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Plus, Trash2, Edit2, ChevronRight, User } from 'lucide-react'
import { usersApi, rolesApi, tenantApi, type User as UserType } from '../api/client'
import { Button } from '../components/ui/Button'
import { Input, Select } from '../components/ui/Input'
import { Badge, RoleBadge } from '../components/ui/Badge'
import { Dialog } from '../components/ui/Dialog'
import { Skeleton } from '../components/ui/Skeleton'
import { EmptyState } from '../components/ui/EmptyState'
import { useToast } from '../components/ui/Toast'

const EMPTY: Omit<UserType, 'id' | 'adminDomains' | 'isActive' | 'createdAt'> = {
  username: '',
  email: '',
  roles: [],
  book: [],
  segments: [],
  classification: 'internal',
  team: '',
}

const CLASSIFICATION_COLORS: Record<string, string> = {
  public: 'bg-green-100 text-green-800',
  internal: 'bg-blue-100 text-blue-800',
  confidential: 'bg-amber-100 text-amber-800',
  restricted: 'bg-red-100 text-red-800',
}

function getClassificationColor(classification: string): string {
  return CLASSIFICATION_COLORS[classification] || 'bg-slate-100 text-slate-800'
}

function SkeletonRow() {
  return (
    <tr className="border-b border-slate-100">
      <td className="px-5 py-3.5">
        <div className="flex items-center gap-3">
          <Skeleton className="w-8 h-8 rounded-full" />
          <div className="flex-1">
            <Skeleton className="h-4 w-32 mb-1" />
            <Skeleton className="h-3 w-40" />
          </div>
        </div>
      </td>
      <td className="px-5 py-3.5"><Skeleton className="h-4 w-24" /></td>
      <td className="px-5 py-3.5"><Skeleton className="h-4 w-20" /></td>
      <td className="px-5 py-3.5"><Skeleton className="h-4 w-24" /></td>
      <td className="px-5 py-3.5"><Skeleton className="h-4 w-28" /></td>
      <td className="px-5 py-3.5"><Skeleton className="h-4 w-16" /></td>
    </tr>
  )
}

export function Users() {
  const qc = useQueryClient()
  const { toast } = useToast()
  const [open, setOpen] = useState(false)
  const [editing, setEdit] = useState<UserType | null>(null)
  const [uid, setUid] = useState('')
  const [form, setForm] = useState({ ...EMPTY })
  const [detail, setDetail] = useState<UserType | null>(null)
  const [search, setSearch] = useState('')

  const { data: users = [], isLoading } = useQuery({
    queryKey: ['users'],
    queryFn: usersApi.list,
  })
  const { data: roles = [] } = useQuery({
    queryKey: ['roles'],
    queryFn: rolesApi.list,
  })
  const { data: classifications = [] } = useQuery({
    queryKey: ['classifications'],
    queryFn: tenantApi.getClassificationSchema,
  })

  const createMut = useMutation({
    mutationFn: () => usersApi.create(uid, form),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['users'] })
      close()
      toast('success', 'User created')
    },
    onError: (e: Error) => toast('error', e.message),
  })

  const updateMut = useMutation({
    mutationFn: () => usersApi.update(editing!.id, form),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['users'] })
      close()
      toast('success', 'User updated')
    },
    onError: (e: Error) => toast('error', e.message),
  })

  const deleteMut = useMutation({
    mutationFn: (id: string) => usersApi.delete(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['users'] })
      toast('success', 'User deleted')
    },
    onError: (e: Error) => toast('error', e.message),
  })

  const assignRoleMut = useMutation({
    mutationFn: ({ userId, roleId }: { userId: string; roleId: string }) =>
      usersApi.assignRole(userId, roleId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['users'] }),
    onError: (e: Error) => toast('error', e.message),
  })

  const removeRoleMut = useMutation({
    mutationFn: ({ userId, roleId }: { userId: string; roleId: string }) =>
      usersApi.removeRole(userId, roleId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['users'] }),
    onError: (e: Error) => toast('error', e.message),
  })

  function openCreate() {
    setEdit(null)
    setUid('')
    setForm({ ...EMPTY })
    setOpen(true)
  }
  function openEdit(u: UserType) {
    setEdit(u)
    setUid(u.id)
    setForm({
      username: u.username,
      email: u.email,
      roles: u.roles,
      book: u.book,
      segments: u.segments,
      classification: u.classification,
      team: u.team,
    })
    setOpen(true)
  }
  function close() {
    setOpen(false)
    setEdit(null)
  }

  const filtered = users.filter(
    u =>
      u.username.toLowerCase().includes(search.toLowerCase()) ||
      u.id.toLowerCase().includes(search.toLowerCase()) ||
      u.email.toLowerCase().includes(search.toLowerCase())
  )

  const detailUser = detail ? users.find(u => u.id === detail.id) ?? detail : null

  return (
    <div className="px-8 py-8">
      {/* Header */}
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-bold text-slate-900">Users</h1>
          <p className="text-sm text-slate-500 mt-1">
            {users.length} principal{users.length !== 1 ? 's' : ''} in directory
          </p>
        </div>
        <Button onClick={openCreate}>
          <Plus size={15} /> Add user
        </Button>
      </div>

      {/* Search */}
      <div className="mb-4">
        <Input
          placeholder="Search by name, ID, or email…"
          value={search}
          onChange={e => setSearch(e.target.value)}
          className="max-w-xs"
        />
      </div>

      {/* Table */}
      <div className="bg-white rounded-lg border border-slate-200 overflow-hidden">
        {isLoading ? (
          <table className="w-full text-sm">
            <thead className="bg-slate-50 border-b border-slate-200">
              <tr>
                {['User', 'Roles', 'Classification', 'Team', 'Segments', ''].map(h => (
                  <th
                    key={h}
                    className="text-left px-5 py-3 text-xs font-medium text-slate-500 uppercase tracking-wide"
                  >
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {[...Array(5)].map((_, i) => (
                <SkeletonRow key={i} />
              ))}
            </tbody>
          </table>
        ) : filtered.length === 0 ? (
          <EmptyState
            icon={<User size={40} className="text-slate-300" />}
            title="No users found"
            description={
              users.length === 0
                ? 'Create your first user to get started'
                : 'Try adjusting your search'
            }
            action={
              users.length === 0
                ? { label: 'Create user', onClick: openCreate }
                : undefined
            }
          />
        ) : (
          <table className="w-full text-sm">
            <thead className="bg-slate-50 border-b border-slate-200">
              <tr>
                {['User', 'Roles', 'Classification', 'Team', 'Segments', ''].map(h => (
                  <th
                    key={h}
                    className="text-left px-5 py-3 text-xs font-medium text-slate-500 uppercase tracking-wide"
                  >
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {filtered.map(u => (
                <tr key={u.id} className="hover:bg-slate-50 transition-colors group">
                  <td className="px-5 py-3.5">
                    <div className="flex items-center gap-3">
                      <div className="w-8 h-8 rounded-full bg-brand-50 flex items-center justify-center shrink-0">
                        <span className="text-brand-700 text-sm font-semibold">
                          {u.username.charAt(0)}
                        </span>
                      </div>
                      <div>
                        <p className="font-medium text-slate-900">{u.username}</p>
                        <p className="text-xs text-slate-500">
                          {u.id} {u.email && `· ${u.email}`}
                        </p>
                      </div>
                    </div>
                  </td>
                  <td className="px-5 py-3.5">
                    <div className="flex flex-wrap gap-1">
                      {u.roles.length > 0 ? (
                        u.roles.map(r => <RoleBadge key={r} role={r} />)
                      ) : (
                        <span className="text-slate-400 text-xs">—</span>
                      )}
                    </div>
                  </td>
                  <td className="px-5 py-3.5">
                    <Badge color={u.classification === 'restricted' ? 'red' : u.classification === 'confidential' ? 'yellow' : u.classification === 'internal' ? 'blue' : 'green'}>
                      {u.classification}
                    </Badge>
                  </td>
                  <td className="px-5 py-3.5 text-slate-600 text-xs">{u.team || '—'}</td>
                  <td className="px-5 py-3.5">
                    <div className="flex gap-1">
                      {u.segments.map(s => (
                        <Badge key={s} color="green">
                          {s}
                        </Badge>
                      ))}
                    </div>
                  </td>
                  <td className="px-5 py-3.5">
                    <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                      <button
                        onClick={() => setDetail(u)}
                        className="p-1.5 text-slate-400 hover:text-brand-600 hover:bg-brand-50 rounded-md transition-colors"
                        title="View"
                      >
                        <ChevronRight size={14} />
                      </button>
                      <button
                        onClick={() => openEdit(u)}
                        className="p-1.5 text-slate-400 hover:text-slate-700 hover:bg-slate-100 rounded-md transition-colors"
                        title="Edit"
                      >
                        <Edit2 size={14} />
                      </button>
                      <button
                        onClick={() => deleteMut.mutate(u.id)}
                        className="p-1.5 text-slate-400 hover:text-red-600 hover:bg-red-50 rounded-md transition-colors"
                        title="Delete"
                      >
                        <Trash2 size={14} />
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {/* Create / Edit dialog */}
      <Dialog
        open={open}
        onClose={close}
        title={editing ? `Edit ${editing.username}` : 'Add user'}
        description={editing ? `ID: ${editing.id}` : 'Create a new principal'}
      >
        <div className="space-y-4">
          {!editing && (
            <Input
              label="User ID"
              placeholder="rm_alice"
              value={uid}
              onChange={e => setUid(e.target.value)}
              required
              hint="Unique identifier, used in JWT sub claim"
            />
          )}
          <Input
            label="Username"
            value={form.username}
            onChange={e => setForm(f => ({ ...f, username: e.target.value }))}
            required
          />
          <Input
            label="Email"
            type="email"
            value={form.email}
            onChange={e => setForm(f => ({ ...f, email: e.target.value }))}
          />
          <Select
            label="Classification"
            value={form.classification}
            onChange={e => setForm(f => ({ ...f, classification: e.target.value }))}
          >
            {classifications.length > 0 ? (
              classifications.map(c => (
                <option key={c.name} value={c.name}>
                  {c.name}
                </option>
              ))
            ) : (
              <>
                <option value="public">Public</option>
                <option value="internal">Internal</option>
                <option value="confidential">Confidential</option>
                <option value="restricted">Restricted</option>
              </>
            )}
          </Select>
          <Input
            label="Team"
            placeholder="wealth-private-banking"
            value={form.team}
            onChange={e => setForm(f => ({ ...f, team: e.target.value }))}
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
          <div className="flex flex-col gap-1">
            <label className="text-sm font-medium text-slate-700">Roles</label>
            <div className="flex flex-wrap gap-2">
              {roles.map(r => (
                <label key={r.id} className="flex items-center gap-1.5 text-sm cursor-pointer">
                  <input
                    type="checkbox"
                    checked={form.roles.includes(r.id)}
                    onChange={e =>
                      setForm(f => ({
                        ...f,
                        roles: e.target.checked
                          ? [...f.roles, r.id]
                          : f.roles.filter(x => x !== r.id),
                      }))
                    }
                    className="rounded border-slate-300 text-brand-600 focus:ring-brand-500"
                  />
                  <span className="text-slate-700">{r.name}</span>
                </label>
              ))}
            </div>
          </div>
          <div className="flex gap-2 pt-2 border-t border-slate-100">
            <Button variant="secondary" onClick={close} className="flex-1">
              Cancel
            </Button>
            <Button
              className="flex-1"
              loading={createMut.isPending || updateMut.isPending}
              onClick={() => (editing ? updateMut.mutate() : createMut.mutate())}
            >
              {editing ? 'Save changes' : 'Create user'}
            </Button>
          </div>
        </div>
      </Dialog>

      {/* Detail panel */}
      <Dialog
        open={!!detail}
        onClose={() => setDetail(null)}
        title={detailUser?.username ?? ''}
        description={`${detailUser?.id} · ${detailUser?.email}`}
        size="lg"
      >
        {detailUser && (
          <div className="space-y-5">
            <div className="grid grid-cols-2 gap-4">
              <div className="bg-slate-50 rounded-lg p-4">
                <p className="text-xs font-medium text-slate-500 mb-2">ROLES</p>
                <div className="flex flex-wrap gap-1.5">
                  {detailUser.roles.map(r => (
                    <RoleBadge key={r} role={r} />
                  ))}
                </div>
                <div className="mt-3 flex flex-wrap gap-1">
                  {roles
                    .filter(r => !detailUser.roles.includes(r.id))
                    .map(r => (
                      <button
                        key={r.id}
                        onClick={() => assignRoleMut.mutate({ userId: detailUser.id, roleId: r.id })}
                        className="text-xs px-2 py-1 border border-dashed border-slate-300 rounded text-slate-500 hover:border-brand-400 hover:text-brand-600 transition-colors"
                      >
                        + {r.id}
                      </button>
                    ))}
                </div>
              </div>
              <div className="bg-slate-50 rounded-lg p-4">
                <p className="text-xs font-medium text-slate-500 mb-2">ATTRIBUTES</p>
                <dl className="space-y-1.5 text-sm">
                  <div className="flex justify-between">
                    <dt className="text-slate-500">Classification</dt>
                    <dd className="font-medium capitalize">{detailUser.classification}</dd>
                  </div>
                  <div className="flex justify-between">
                    <dt className="text-slate-500">Team</dt>
                    <dd className="font-medium">{detailUser.team || '—'}</dd>
                  </div>
                  <div className="flex justify-between">
                    <dt className="text-slate-500">Segments</dt>
                    <dd className="font-medium">
                      {detailUser.segments.join(', ') || '—'}
                    </dd>
                  </div>
                </dl>
              </div>
            </div>
            <div className="bg-slate-50 rounded-lg p-4">
              <p className="text-xs font-medium text-slate-500 mb-2">
                BOOK (relationship IDs)
              </p>
              {detailUser.book.length > 0 ? (
                <div className="flex flex-wrap gap-1.5">
                  {detailUser.book.map(r => (
                    <Badge key={r} color="blue">
                      {r}
                    </Badge>
                  ))}
                </div>
              ) : (
                <p className="text-sm text-slate-400">No relationships in book</p>
              )}
            </div>
          </div>
        )}
      </Dialog>
    </div>
  )
}
