import { useMemo, useState } from 'react'
import { clsx } from 'clsx'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { AlertCircle, Plus, RefreshCw, Trash2, Edit2, ChevronRight, User, X } from 'lucide-react'
import { usersApi, rolesApi, adminApi, tenantApi, type User as UserType } from '../api/client'
import { Button } from '../components/ui/Button'
import { Input } from '../components/ui/Input'
import { Badge, RoleBadge } from '../components/ui/Badge'
import { Dialog } from '../components/ui/Dialog'
import { Skeleton } from '../components/ui/Skeleton'
import { EmptyState } from '../components/ui/EmptyState'
import { useToast } from '../components/ui/Toast'

type BadgeColor = 'blue' | 'green' | 'yellow' | 'red' | 'slate'

/** A single "segment @ clearance-tier" pairing in the form. */
interface SegmentRow {
  segment: string
  tier: string
}

interface UserForm {
  username: string
  email: string
  password: string
  roleIds: string[]
  segments: SegmentRow[]
  team: string
}

const EMPTY: UserForm = {
  username: '',
  email: '',
  password: '',
  roleIds: [],
  segments: [],
  team: '',
}

const TABLE_COLUMNS = ['User', 'Roles', 'Segments & clearance', 'Team', '']

/** Colour a clearance chip by its schema rank (higher rank = more sensitive). */
function tierColor(rank: number | undefined): BadgeColor {
  if (rank === undefined) return 'slate'
  if (rank >= 3) return 'red'
  if (rank === 2) return 'yellow'
  if (rank === 1) return 'blue'
  return 'green'
}

/** Bare-select styling that matches the shared Select component (design-system parity). */
function selectClass(hasError: boolean): string {
  return clsx(
    'w-full px-3 py-2 text-sm border rounded-md bg-white/95 text-ink-900 focus:outline-none focus:ring-2 focus:ring-gold-300 focus:border-axiom-700 transition-colors shadow-sm',
    hasError ? 'border-red-400' : 'border-line',
  )
}

interface SegmentValidation {
  rowErrors: string[]
  message: string | null
  valid: boolean
}

/** Deterministic client-side validation for the per-segment rows. */
function validateSegments(rows: SegmentRow[]): SegmentValidation {
  const rowErrors = rows.map(() => '')
  const seen = new Set<string>()
  rows.forEach((row, i) => {
    if (!row.segment && !row.tier) {
      rowErrors[i] = 'Choose a segment and a clearance tier'
      return
    }
    if (!row.segment) {
      rowErrors[i] = 'Choose a segment'
      return
    }
    if (!row.tier) {
      rowErrors[i] = 'Choose a clearance tier'
      return
    }
    if (seen.has(row.segment)) {
      rowErrors[i] = `“${row.segment}” is already assigned above`
    } else {
      seen.add(row.segment)
    }
  })
  const message = rows.length === 0 ? 'Add at least one segment to grant access.' : null
  const valid = rows.length > 0 && rowErrors.every((e) => e === '')
  return { rowErrors, message, valid }
}

/** The user's overall classification ceiling = the highest-ranked tier across their segments. */
function deriveClassification(rows: SegmentRow[], rankByTier: Map<string, number>): string {
  let best = 'internal'
  let bestRank = -1
  for (const row of rows) {
    if (!row.tier) continue
    const rank = rankByTier.get(row.tier) ?? 0
    if (rank >= bestRank) {
      bestRank = rank
      best = row.tier
    }
  }
  return best
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
      <td className="px-5 py-3.5"><Skeleton className="h-4 w-40" /></td>
      <td className="px-5 py-3.5"><Skeleton className="h-4 w-20" /></td>
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
  const [form, setForm] = useState<UserForm>({ ...EMPTY })
  const [detail, setDetail] = useState<UserType | null>(null)
  const [search, setSearch] = useState('')
  const [deleteTarget, setDeleteTarget] = useState<UserType | null>(null)

  const { data: users = [], isLoading, isError, error, refetch } = useQuery({
    queryKey: ['users'],
    queryFn: usersApi.list,
  })
  const { data: roles = [] } = useQuery({
    queryKey: ['roles'],
    queryFn: rolesApi.list,
  })
  const {
    data: classifications = [],
    isLoading: tiersLoading,
    isError: tiersError,
    refetch: refetchTiers,
  } = useQuery({
    queryKey: ['classifications'],
    queryFn: tenantApi.getClassificationSchema,
  })
  const {
    data: segmentsData,
    isLoading: segsLoading,
    isError: segsError,
    refetch: refetchSegs,
  } = useQuery({
    queryKey: ['segments'],
    queryFn: adminApi.segments,
  })

  const liveSegments = useMemo(() => segmentsData?.segments ?? [], [segmentsData])
  const rankByTier = useMemo(
    () => new Map(classifications.map((c) => [c.name, c.rank])),
    [classifications],
  )
  const roleIdByName = useMemo(
    () => new Map(roles.map((role) => [role.name, role.id])),
    [roles],
  )
  const chatUserRoleId = useMemo(
    () => roles.find((r) => r.name === 'chat_user')?.id,
    [roles],
  )

  const segValidation = useMemo(() => validateSegments(form.segments), [form.segments])
  const optionsLoading = segsLoading || tiersLoading
  const optionsError = segsError || tiersError

  const baseValid = editing
    ? true
    : Boolean(uid.trim()) && Boolean(form.password.trim())
  const canSave = baseValid && Boolean(form.username.trim()) && segValidation.valid

  function buildAttributes() {
    const segments: Record<string, string> = {}
    for (const row of form.segments) {
      if (row.segment && row.tier) segments[row.segment] = row.tier
    }
    return {
      segments,
      classification: deriveClassification(form.segments, rankByTier),
      team: form.team,
    }
  }

  const createMut = useMutation({
    mutationFn: async () => {
      if (!uid.trim()) throw new Error('User ID is required')
      if (!form.password.trim()) throw new Error('Password is required')
      if (!segValidation.valid) throw new Error('Fix the segment rows before saving')
      const created = await usersApi.create({
        id: uid.trim(),
        username: form.username.trim(),
        email: form.email.trim(),
        password: form.password,
        attributes: buildAttributes(),
      })
      await Promise.all(form.roleIds.map((roleId) => usersApi.assignRole(created.id, roleId)))
      return created
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['users'] })
      close()
      toast('success', 'User created')
    },
    onError: (e: Error) => toast('error', e.message),
  })

  const updateMut = useMutation({
    mutationFn: async () => {
      if (!editing) throw new Error('No user selected')
      if (!segValidation.valid) throw new Error('Fix the segment rows before saving')
      const beforeRoleIds = editing.roles
        .map((name) => roleIdByName.get(name))
        .filter((roleId): roleId is string => Boolean(roleId))
      const desired = new Set(form.roleIds)
      const before = new Set(beforeRoleIds)

      const updated = await usersApi.update(editing.id, {
        email: form.email.trim(),
        attributes: buildAttributes(),
      })

      await Promise.all([
        ...form.roleIds
          .filter((roleId) => !before.has(roleId))
          .map((roleId) => usersApi.assignRole(editing.id, roleId)),
        ...beforeRoleIds
          .filter((roleId) => !desired.has(roleId))
          .map((roleId) => usersApi.removeRole(editing.id, roleId)),
      ])
      return updated
    },
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
      setDeleteTarget(null)
      toast('success', 'User deleted')
    },
    onError: (e: Error) => toast('error', e.message),
  })

  const assignRoleMut = useMutation({
    mutationFn: ({ userId, roleId }: { userId: string; roleId: string }) =>
      usersApi.assignRole(userId, roleId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['users'] })
      toast('success', 'Role assigned')
    },
    onError: (e: Error) => toast('error', e.message),
  })

  function openCreate() {
    setEdit(null)
    setUid('')
    setForm({
      ...EMPTY,
      segments: [{ segment: '', tier: '' }],
      roleIds: chatUserRoleId ? [chatUserRoleId] : [],
    })
    setOpen(true)
  }
  function openEdit(u: UserType) {
    setEdit(u)
    setUid(u.id)
    setForm({
      username: u.username,
      email: u.email,
      password: '',
      roleIds: u.roles
        .map((name) => roleIdByName.get(name))
        .filter((roleId): roleId is string => Boolean(roleId)),
      segments: Object.entries(u.segments ?? {}).map(([segment, tier]) => ({ segment, tier })),
      team: u.team || '',
    })
    setOpen(true)
  }
  function close() {
    setOpen(false)
    setEdit(null)
  }

  // ── per-row helpers ──────────────────────────────────────────────────────────
  function setRow(i: number, patch: Partial<SegmentRow>) {
    setForm((f) => ({
      ...f,
      segments: f.segments.map((r, idx) => (idx === i ? { ...r, ...patch } : r)),
    }))
  }
  function addRow() {
    setForm((f) => ({ ...f, segments: [...f.segments, { segment: '', tier: '' }] }))
  }
  function removeRow(i: number) {
    setForm((f) => ({ ...f, segments: f.segments.filter((_, idx) => idx !== i) }))
  }

  /** Options for a row's segment select: live segments ∪ stored value, minus rows chosen elsewhere. */
  function segmentOptions(rowIndex: number): string[] {
    const chosenElsewhere = new Set(
      form.segments.filter((_, i) => i !== rowIndex).map((r) => r.segment).filter(Boolean),
    )
    const base = new Set<string>(liveSegments)
    const current = form.segments[rowIndex]?.segment
    if (current) base.add(current)
    return [...base].filter((s) => !chosenElsewhere.has(s)).sort()
  }

  const usedSegments = new Set(form.segments.map((r) => r.segment).filter(Boolean))
  const availableSegments = new Set<string>([...liveSegments, ...usedSegments])
  const hasEmptyRow = form.segments.some((r) => !r.segment)
  const canAddRow = usedSegments.size < availableSegments.size && !hasEmptyRow

  const filtered = users.filter(
    (u) =>
      u.username.toLowerCase().includes(search.toLowerCase()) ||
      u.id.toLowerCase().includes(search.toLowerCase()) ||
      u.email.toLowerCase().includes(search.toLowerCase()),
  )

  const detailUser = detail ? users.find((u) => u.id === detail.id) ?? detail : null

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
          onChange={(e) => setSearch(e.target.value)}
          className="max-w-xs"
        />
      </div>

      {isError && (
        <div className="mb-4 flex items-center gap-3 rounded-lg border border-red-200 bg-red-50 p-4" role="alert">
          <AlertCircle size={18} className="shrink-0 text-red-700" />
          <p className="flex-1 text-sm font-medium text-red-900">
            {error instanceof Error ? error.message : 'Users could not be loaded'}
          </p>
          <Button size="sm" variant="secondary" onClick={() => { void refetch() }}>
            <RefreshCw size={14} />
            Retry
          </Button>
        </div>
      )}

      {/* Table */}
      <div className="bg-white rounded-lg border border-slate-200 overflow-hidden">
        {isLoading ? (
          <table className="w-full text-sm">
            <thead className="bg-slate-50 border-b border-slate-200">
              <tr>
                {TABLE_COLUMNS.map((h) => (
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
                {TABLE_COLUMNS.map((h) => (
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
              {filtered.map((u) => {
                const segEntries = Object.entries(u.segments ?? {})
                return (
                  <tr key={u.id} className="hover:bg-slate-50 transition-colors group">
                    <td className="px-5 py-3.5">
                      <div className="flex items-center gap-3">
                        <div className="w-8 h-8 rounded-full bg-axiom-50 flex items-center justify-center shrink-0 ring-1 ring-axiom-600/10">
                          <span className="text-axiom-800 text-sm font-semibold">
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
                          u.roles.map((r) => <RoleBadge key={r} role={r} />)
                        ) : (
                          <span className="text-slate-400 text-xs">—</span>
                        )}
                      </div>
                    </td>
                    <td className="px-5 py-3.5">
                      <div className="flex flex-wrap gap-1">
                        {segEntries.length > 0 ? (
                          segEntries.map(([seg, tier]) => (
                            <Badge key={seg} color={tierColor(rankByTier.get(tier))}>
                              <span className="font-semibold">{seg}</span>
                              <span className="mx-1 opacity-50">·</span>
                              {tier}
                            </Badge>
                          ))
                        ) : (
                          <span className="text-slate-400 text-xs">—</span>
                        )}
                      </div>
                    </td>
                    <td className="px-5 py-3.5 text-slate-600 text-xs">{u.team || '—'}</td>
                    <td className="px-5 py-3.5">
                      <div className="flex items-center gap-1 opacity-0 transition-opacity group-hover:opacity-100 group-focus-within:opacity-100">
                        <button
                          onClick={() => setDetail(u)}
                          className="p-1.5 text-slate-400 hover:text-axiom-700 hover:bg-axiom-50 rounded-md transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-gold-300"
                          title="View"
                          aria-label={`View ${u.username}`}
                        >
                          <ChevronRight size={14} />
                        </button>
                        <button
                          onClick={() => openEdit(u)}
                          className="p-1.5 text-slate-400 hover:text-slate-700 hover:bg-slate-100 rounded-md transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-gold-300"
                          title="Edit"
                          aria-label={`Edit ${u.username}`}
                        >
                          <Edit2 size={14} />
                        </button>
                        <button
                          onClick={() => setDeleteTarget(u)}
                          className="p-1.5 text-slate-400 hover:text-red-600 hover:bg-red-50 rounded-md transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-gold-300"
                          title="Delete"
                          aria-label={`Delete ${u.username}`}
                        >
                          <Trash2 size={14} />
                        </button>
                      </div>
                    </td>
                  </tr>
                )
              })}
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
              onChange={(e) => setUid(e.target.value)}
              required
              hint="Unique identifier, used in JWT sub claim"
            />
          )}
          <Input
            label="Username"
            value={form.username}
            onChange={(e) => setForm((f) => ({ ...f, username: e.target.value }))}
            disabled={!!editing}
            required
          />
          <Input
            label="Email"
            type="email"
            value={form.email}
            onChange={(e) => setForm((f) => ({ ...f, email: e.target.value }))}
          />
          {!editing && (
            <Input
              label="Password"
              type="password"
              value={form.password}
              onChange={(e) => setForm((f) => ({ ...f, password: e.target.value }))}
              required
            />
          )}

          {/* Segments & clearance — per-segment row editor */}
          <div className="flex flex-col gap-2">
            <div className="flex items-baseline justify-between">
              <label className="text-sm font-medium text-ink-700">Segments &amp; clearance</label>
              <span className="text-xs text-ink-500">Data tier held per business segment</span>
            </div>

            {optionsLoading ? (
              <div className="flex flex-col gap-2" aria-live="polite" aria-busy="true">
                <Skeleton className="h-9 w-full" />
                <Skeleton className="h-9 w-full" />
              </div>
            ) : optionsError ? (
              <div className="flex items-center gap-2 rounded-md border border-red-200 bg-red-50 p-3" role="alert">
                <AlertCircle size={16} className="shrink-0 text-red-700" />
                <p className="flex-1 text-xs font-medium text-red-900">
                  Couldn’t load segment or clearance options.
                </p>
                <Button
                  size="sm"
                  variant="secondary"
                  onClick={() => { void refetchSegs(); void refetchTiers() }}
                >
                  <RefreshCw size={13} /> Retry
                </Button>
              </div>
            ) : (
              <>
                {form.segments.length === 0 ? (
                  <div className="rounded-md border border-dashed border-line bg-slate-50/60 p-4 text-center">
                    <p className="text-xs text-ink-500">
                      No segments yet — add at least one to grant this user access.
                    </p>
                  </div>
                ) : (
                  <div className="flex flex-col gap-2">
                    {form.segments.map((row, i) => {
                      const rowError = segValidation.rowErrors[i]
                      const opts = segmentOptions(i)
                      return (
                        <div key={i} className="flex flex-col gap-1">
                          <div className="flex items-start gap-2">
                            <div className="flex-1">
                              <select
                                aria-label={`Segment for row ${i + 1}`}
                                aria-invalid={Boolean(rowError)}
                                value={row.segment}
                                onChange={(e) => setRow(i, { segment: e.target.value })}
                                className={selectClass(Boolean(rowError))}
                              >
                                <option value="">Segment…</option>
                                {opts.map((s) => (
                                  <option key={s} value={s}>{s}</option>
                                ))}
                              </select>
                            </div>
                            <div className="flex-1">
                              <select
                                aria-label={`Clearance tier for row ${i + 1}`}
                                aria-invalid={Boolean(rowError)}
                                value={row.tier}
                                onChange={(e) => setRow(i, { tier: e.target.value })}
                                className={selectClass(Boolean(rowError))}
                              >
                                <option value="">Clearance…</option>
                                {classifications.map((c) => (
                                  <option key={c.name} value={c.name}>{c.name}</option>
                                ))}
                              </select>
                            </div>
                            <button
                              type="button"
                              onClick={() => removeRow(i)}
                              aria-label={`Remove ${row.segment || `segment row ${i + 1}`}`}
                              className="mt-0.5 p-2 text-slate-400 hover:text-red-600 hover:bg-red-50 rounded-md transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-gold-300"
                            >
                              <X size={15} />
                            </button>
                          </div>
                          {rowError && (
                            <p className="text-xs text-red-600" role="alert">{rowError}</p>
                          )}
                        </div>
                      )
                    })}
                  </div>
                )}

                <div className="flex items-center justify-between">
                  <button
                    type="button"
                    onClick={addRow}
                    disabled={!canAddRow}
                    className="inline-flex items-center gap-1.5 text-sm font-medium text-axiom-700 hover:text-axiom-900 disabled:opacity-40 disabled:pointer-events-none focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-gold-300 rounded px-1 py-0.5"
                  >
                    <Plus size={14} /> Add segment
                  </button>
                  {segValidation.message && (
                    <p className="text-xs text-red-600" role="alert">{segValidation.message}</p>
                  )}
                </div>
              </>
            )}
          </div>

          <Input
            label="Team"
            placeholder="wealth-private-banking"
            value={form.team}
            onChange={(e) => setForm((f) => ({ ...f, team: e.target.value }))}
          />

          <div className="flex flex-col gap-1">
            <label className="text-sm font-medium text-slate-700">Roles</label>
            <div className="flex flex-wrap gap-2">
              {roles.map((r) => (
                <label key={r.id} className="flex items-center gap-1.5 text-sm cursor-pointer">
                  <input
                    type="checkbox"
                    checked={form.roleIds.includes(r.id)}
                    onChange={(e) =>
                      setForm((f) => ({
                        ...f,
                        roleIds: e.target.checked
                          ? [...f.roleIds, r.id]
                          : f.roleIds.filter((x) => x !== r.id),
                      }))
                    }
                    className="rounded border-slate-300 text-axiom-700 focus:ring-gold-300"
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
              disabled={!canSave}
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
                  {detailUser.roles.map((r) => (
                    <RoleBadge key={r} role={r} />
                  ))}
                </div>
                <div className="mt-3 flex flex-wrap gap-1">
                  {roles
                    .filter((r) => !detailUser.roles.includes(r.id))
                    .map((r) => (
                      <button
                        key={r.id}
                        onClick={() => assignRoleMut.mutate({ userId: detailUser.id, roleId: r.id })}
                        className="text-xs px-2 py-1 border border-dashed border-slate-300 rounded text-slate-500 hover:border-axiom-400 hover:text-axiom-700 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-gold-300"
                      >
                        + {r.id}
                      </button>
                    ))}
                </div>
              </div>
              <div className="bg-slate-50 rounded-lg p-4">
                <p className="text-xs font-medium text-slate-500 mb-2">SEGMENTS &amp; CLEARANCE</p>
                {Object.entries(detailUser.segments ?? {}).length > 0 ? (
                  <div className="flex flex-col gap-1.5">
                    {Object.entries(detailUser.segments).map(([seg, tier]) => (
                      <div key={seg} className="flex items-center justify-between text-sm">
                        <span className="font-medium text-slate-700">{seg}</span>
                        <Badge color={tierColor(rankByTier.get(tier))}>{tier}</Badge>
                      </div>
                    ))}
                  </div>
                ) : (
                  <p className="text-sm text-slate-400">No segments assigned</p>
                )}
                <dl className="mt-3 space-y-1.5 text-sm border-t border-slate-200 pt-3">
                  <div className="flex justify-between">
                    <dt className="text-slate-500">Ceiling</dt>
                    <dd className="font-medium capitalize">{detailUser.classification}</dd>
                  </div>
                  <div className="flex justify-between">
                    <dt className="text-slate-500">Team</dt>
                    <dd className="font-medium">{detailUser.team || '—'}</dd>
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
                  {detailUser.book.map((r) => (
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

      <Dialog
        open={!!deleteTarget}
        onClose={() => setDeleteTarget(null)}
        title="Delete user"
        description={deleteTarget ? `Deactivate ${deleteTarget.username}` : undefined}
        size="sm"
      >
        <div className="space-y-4">
          <p className="text-sm leading-6 text-ink-600">
            This will deactivate the user and remove them from active admin workflows.
          </p>
          <div className="flex gap-2 border-t border-line pt-4">
            <Button type="button" variant="secondary" className="flex-1" onClick={() => setDeleteTarget(null)}>
              Cancel
            </Button>
            <Button
              type="button"
              variant="danger"
              className="flex-1"
              loading={deleteMut.isPending}
              onClick={() => deleteTarget && deleteMut.mutate(deleteTarget.id)}
            >
              Delete
            </Button>
          </div>
        </div>
      </Dialog>
    </div>
  )
}
