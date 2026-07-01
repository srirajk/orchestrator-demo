import { readAdminToken } from '../auth/tokenStorage'

const BASE = '/api'

function token(): string {
  return readAdminToken()
}

async function req<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token() ? { Authorization: `Bearer ${token()}` } : {}),
    },
    ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
  })
  if (!res.ok) {
    const err = await res.json().catch(() => ({ detail: res.statusText }))
    throw new Error(err.detail?.message || err.detail || res.statusText)
  }
  if (res.status === 204) return undefined as T
  return res.json()
}

// ── Auth ──────────────────────────────────────────────────────────────────────
export const authApi = {
  login: (username: string, password: string) =>
    req<{ accessToken: string; tokenType: string; expiresIn: number; user: User }>('POST', '/auth/login', { username, password }),
}

// ── Types ─────────────────────────────────────────────────────────────────────
export interface User {
  id: string
  username: string
  email: string
  roles: string[]
  book: string[]
  segments: string[]
  clearance?: number
  classification: string
  team?: string
  adminDomains: string[]
  isActive?: boolean
  createdAt?: string
}

export interface AuditEntry {
  id: string
  tenantId: string
  actorId: string
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
  filename: string
  policy_type: string
  resource: string
  size: number
  content?: string
  error?: string
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
  list: () => req<User[]>('GET', '/users'),
  get: (id: string) => req<User>('GET', `/users/${id}`),
  create: (id: string, data: Omit<User, 'id' | 'adminDomains' | 'isActive' | 'createdAt'>) =>
    req<User>('POST', `/users?user_id=${encodeURIComponent(id)}`, data),
  update: (id: string, data: Omit<User, 'id' | 'adminDomains' | 'isActive' | 'createdAt'>) =>
    req<User>('PUT', `/users/${id}`, data),
  delete: (id: string) => req<void>('DELETE', `/users/${id}`),
  patchBook: (id: string, add: string[], remove: string[]) =>
    req<User>('PATCH', `/users/${id}/book`, { add, remove }),
  assignRole: (userId: string, roleId: string) =>
    req<{ roles: string[] }>('POST', `/users/${userId}/roles`, { role_id: roleId }),
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
    req<Team>('POST', '/teams', data),
  update: (id: string, data: Omit<Team, 'id' | 'memberCount'>) =>
    req<Team>('PUT', `/teams/${id}`, { id, ...data }),
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
  list: () => req<Role[]>('GET', '/roles'),
  get: (id: string) => req<Role>('GET', `/roles/${id}`),
  create: (data: Role) => req<Role>('POST', '/roles', data),
  update: (id: string, data: Omit<Role, 'id'>) =>
    req<Role>('PUT', `/roles/${id}`, { id, ...data }),
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
  list: () => req<{ policies: Policy[] }>('GET', '/admin/policies'),
  generate: (intent: PolicyIntent) =>
    req<PolicyResult>('POST', '/admin/policies/generate', intent),
  validate: (yaml: string) =>
    req<{ valid: boolean; errors: string[] }>('POST', '/admin/policies/validate', { yaml }),
  apply: (yaml: string, filename: string) =>
    req<{ applied: boolean; path: string }>('POST', '/admin/policies/apply', { yaml, filename }),
}
