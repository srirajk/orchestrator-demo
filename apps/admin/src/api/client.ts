import { clearAdminToken, notifyAuthLogout, readAdminToken } from '../auth/tokenStorage'

const BASE = '/api'

function token(): string {
  return readAdminToken()
}

function broadcastLogout(): void {
  clearAdminToken()
  notifyAuthLogout()
}

function errorMessage(payload: unknown, fallback: string): string {
  if (!payload || typeof payload !== 'object') return fallback
  const err = payload as Record<string, unknown>
  const detail = err.detail
  if (typeof detail === 'string') return detail
  if (detail && typeof detail === 'object') {
    const message = (detail as Record<string, unknown>).message
    if (typeof message === 'string') return message
  }
  if (typeof err.message === 'string') return err.message
  if (typeof err.error === 'string') return err.error
  return fallback
}

async function req<T>(method: string, path: string, body?: unknown): Promise<T> {
  const authToken = token()
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(authToken ? { Authorization: `Bearer ${authToken}` } : {}),
    },
    ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
  })
  if (!res.ok) {
    if (res.status === 401) broadcastLogout()
    const err = await res.json().catch(() => ({ detail: res.statusText }))
    throw new Error(errorMessage(err, res.statusText || `Request failed (${res.status})`))
  }
  if (res.status === 204) return undefined as T
  return res.json()
}

// ── Auth ──────────────────────────────────────────────────────────────────────
export const authApi = {
  login: (username: string, password: string) =>
    req<{ accessToken: string; tokenType: string; expiresIn: number; user: User }>('POST', '/auth/login', { username, password }),
  impersonate: (userId: string) =>
    req<{ accessToken: string; tokenType: string; expiresIn: number; user: User }>('POST', '/auth/impersonate', { userId }),
}

// ── Types ─────────────────────────────────────────────────────────────────────
export interface User {
  id: string
  username: string
  email: string
  roles: string[]
  book: string[]
  /**
   * ABAC per-segment clearance MAP: `{ segment -> data tier }` (AUTHZ-SPEC §1). Each key is a
   * business segment the user belongs to; each value is the data-classification ceiling they
   * hold in that segment. This is exactly the shape baked into the JWT `segments` claim.
   */
  segments: Record<string, string>
  clearance?: number
  classification: string
  team?: string
  adminDomains: string[]
  isActive?: boolean
  createdAt?: string
}

export interface CreateUserInput {
  id: string
  username: string
  email: string
  password: string
  attributes: Record<string, unknown>
}

export interface UpdateUserInput {
  email?: string
  isActive?: boolean
  attributes?: Record<string, unknown>
}

export interface AuditEntry {
  id: string
  tenantId: string
  actorId: string
  clientId?: string
  action: string
  resourceType: string
  resourceId: string
  beforeState?: Record<string, unknown>
  afterState?: Record<string, unknown>
  sourceIp: string
  occurredAt: string
}

export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface Stats {
  totalUsers: number
  totalRoles: number
  totalTeams: number
  totalPolicies: number
}

export interface ClassificationTier {
  name: string
  rank: number
}

export interface Team {
  id: string
  name: string
  domainId?: string
  description: string
  defaultRoles: string[]
  segments: string[]
  allowedDomains: string[]
  memberCount: number
  members?: TeamMember[]
}

export interface TeamMember {
  id: string
  name: string
  email: string
  roles?: string[]
}

export interface Role {
  id: string
  name: string
  description: string
  permissions: string[]
  clearance_required: number
}

export interface Policy {
  id: string
  name: string
  resourceType: string
  status?: string
  content?: string
  createdAt?: string
  updatedAt?: string
}

export interface PolicyIntent {
  resource: string
  subject_roles: string[]
  actions: string[]
  conditions: {
    clearance_min?: number
    segments?: string[]
    non_mutating_only?: boolean
    domain?: string
    custom_cel?: string
  }
  policy_name: string
  description: string
}

export interface PolicyResult {
  yaml: string
  explanation: string
  warnings: string[]
  valid: boolean
  errors: string[]
}

// ── Users ─────────────────────────────────────────────────────────────────────
export const usersApi = {
  listPage: (page = 0, size = 100) => req<PageResponse<User>>('GET', `/users?page=${page}&size=${size}`),
  list: async () => {
    const page = await usersApi.listPage(0, 100)
    return page.content
  },
  get: (id: string) => req<User>('GET', `/users/${id}`),
  create: (data: CreateUserInput) =>
    req<User>('POST', '/users', data),
  update: (id: string, data: UpdateUserInput) =>
    req<User>('PUT', `/users/${id}`, data),
  delete: (id: string) => req<void>('DELETE', `/users/${id}`),
  patchBook: (id: string, add: string[], remove: string[]) =>
    req<User>('PATCH', `/users/${id}/book`, { add, remove }),
  assignRole: (userId: string, roleId: string) =>
    req<{ roles: string[] }>('POST', `/users/${userId}/roles`, { roleId }),
  removeRole: (userId: string, roleId: string) =>
    req<void>('DELETE', `/users/${userId}/roles/${roleId}`),
  getTeams: (userId: string) =>
    req<{ teams: Team[] }>('GET', `/users/${userId}/teams`),
  getBook: (userId: string) =>
    req<{ relationships: string[] }>('GET', `/users/${userId}/book`),
  checkRelAccess: (userId: string, relId: string) =>
    req<{ allowed: boolean }>('GET', `/users/${userId}/relationships/${relId}/access`),
}

// ── Teams ─────────────────────────────────────────────────────────────────────
export const teamsApi = {
  list: () => req<Team[]>('GET', '/teams'),
  get: (id: string) => req<Team>('GET', `/teams/${id}`),
  create: (data: Omit<Team, 'memberCount'>) =>
    req<Team>('POST', '/teams', {
      name: data.name,
      domainId: data.domainId || data.id || undefined,
      description: data.description,
      defaultRoles: data.defaultRoles,
      segments: data.segments,
      allowedDomains: data.allowedDomains,
    }),
  update: (id: string, data: Omit<Team, 'id' | 'memberCount'>) =>
    req<Team>('PUT', `/teams/${id}`, {
      name: data.name,
      domainId: data.domainId || undefined,
      description: data.description,
      defaultRoles: data.defaultRoles,
      segments: data.segments,
      allowedDomains: data.allowedDomains,
    }),
  delete: (id: string) => req<void>('DELETE', `/teams/${id}`),
  addMember: (teamId: string, userId: string) =>
    req<{ added: boolean }>('POST', `/teams/${teamId}/members`, { user_id: userId }),
  removeMember: (teamId: string, userId: string) =>
    req<void>('DELETE', `/teams/${teamId}/members/${userId}`),
  listMembers: (teamId: string) =>
    req<{ members: TeamMember[] }>('GET', `/teams/${teamId}/members`),
}

// ── Roles ─────────────────────────────────────────────────────────────────────
export const rolesApi = {
  list: async () => {
    const roles = await req<Role[]>('GET', '/roles')
    return roles.map((role) => ({
      ...role,
      clearance_required: role.clearance_required ?? 1,
    }))
  },
  get: (id: string) => req<Role>('GET', `/roles/${id}`),
  create: (data: Role) => req<Role>('POST', '/roles', {
    name: data.name,
    description: data.description,
    permissions: data.permissions,
  }),
  update: (id: string, data: Omit<Role, 'id'>) =>
    req<Role>('PUT', `/roles/${id}`, {
      name: data.name,
      description: data.description,
      permissions: data.permissions,
    }),
  delete: (id: string) => req<void>('DELETE', `/roles/${id}`),
}

// ── Admin ─────────────────────────────────────────────────────────────────────
export const adminApi = {
  policyResources: () => req<{ resources: string[] }>('GET', '/admin/policy-resources'),
  segments: () => req<{ segments: string[] }>('GET', '/admin/segments'),
}

// ── Audit ─────────────────────────────────────────────────────────────────────
export const auditApi = {
  list: (page: number, size: number) =>
    req<PageResponse<AuditEntry>>('GET', `/admin/audit?page=${page}&size=${size}`),
  export: () =>
    req<AuditEntry[]>('GET', '/admin/audit/export'),
}

// ── Stats ─────────────────────────────────────────────────────────────────────
export const statsApi = {
  get: () => req<Stats>('GET', '/stats'),
}

// ── Tenant ─────────────────────────────────────────────────────────────────────
export const tenantApi = {
  getClassificationSchema: () =>
    req<ClassificationTier[]>('GET', '/tenants/default/classification-schema'),
}

// ── Policies ──────────────────────────────────────────────────────────────────
export const policiesApi = {
  list: () => req<Policy[]>('GET', '/admin/policies'),
  generate: (intent: PolicyIntent) =>
    req<PolicyResult>('POST', '/admin/policies/generate', intent),
  validate: (yaml: string) =>
    req<{ valid: boolean; errors: string[] }>('POST', '/admin/policies/validate', { yaml }),
  apply: (yaml: string, filename: string) =>
    req<{ applied: boolean; path: string }>('POST', '/admin/policies/apply', { yaml, filename }),
}
