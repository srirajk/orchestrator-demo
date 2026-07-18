import { clearAdminToken, notifyAuthLogout, readAdminToken } from '../auth/tokenStorage'

const BASE = '/api'

/**
 * ABAC segments arrive from the backend as a {segment: data_classification} MAP
 * (e.g. {"platform":"internal"}), not an array — the UI models them as string[]. Coerce to the
 * list of segment names so `.map`/`.length` never blow up on an object. The per-segment
 * classification is carried separately on `classification`.
 */
export function normalizeSegments(raw: unknown): string[] {
  if (Array.isArray(raw)) return raw as string[]
  if (raw && typeof raw === 'object') return Object.keys(raw as Record<string, unknown>)
  return []
}

function withNormalizedSegments<T extends { segments?: unknown }>(entity: T): T {
  return { ...entity, segments: normalizeSegments(entity.segments) }
}

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

async function req<T>(method: string, path: string, body?: unknown, headers?: Record<string, string>): Promise<T> {
  const authToken = token()
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(authToken ? { Authorization: `Bearer ${authToken}` } : {}),
      ...headers,
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
  segments: string[]
  clearance?: number
  classification: string
  team?: string
  adminDomains: string[]
  isActive?: boolean
  createdAt?: string
  tenantId?: string
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
  listPage: (page = 0, size = 100) => req<PageResponse<User>>('GET', `/users?page=${page}&size=${size}`),
  list: async () => {
    const page = await usersApi.listPage(0, 100)
    return page.content.map(withNormalizedSegments)
  },
  get: async (id: string) => withNormalizedSegments(await req<User>('GET', `/users/${id}`)),
  create: (data: CreateUserInput) =>
    req<User>('POST', '/users', data),
  update: (id: string, data: UpdateUserInput) =>
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
  list: async () => (await req<Team[]>('GET', '/teams')).map(withNormalizedSegments),
  get: async (id: string) => withNormalizedSegments(await req<Team>('GET', `/teams/${id}`)),
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
  list: () => req<{ policies: Policy[] }>('GET', '/admin/policies'),
  generate: (intent: PolicyIntent) =>
    req<PolicyResult>('POST', '/admin/policies/generate', intent),
  validate: (yaml: string) =>
    req<{ valid: boolean; errors: string[] }>('POST', '/admin/policies/validate', { yaml }),
  apply: (yaml: string, filename: string) =>
    req<{ applied: boolean; path: string }>('POST', '/admin/policies/apply', { yaml, filename }),
}

// ── Axiom Policy Studio ──────────────────────────────────────────────────────
export type JsonRecord = Record<string, unknown>

export interface ManifestVocabulary {
  resourceKind: string
  actions: string[]
  classifications: string[]
  attributes: string[]
  roles: string[]
  approvedImports: string[]
}

export interface BaseCeiling {
  resourceKind: string
  tuples: Array<{ action: string; role: string }>
  carriesTenantEqualityBackstop: boolean
  reservedIdentities: string[]
}

export interface StudioGroundingSnapshot {
  tenantId: string
  vocabulary: ManifestVocabulary
  baseCeiling: BaseCeiling
  matrix: { cells: FixtureCell[]; fixtureSetHash: string }
  current: BundleSnapshot
  manifestRefs: string[]
}

export interface DraftRequest {
  intent: string
  resourceKind?: string
  subscopesEnabled: boolean
  vocabulary?: ManifestVocabulary
  baseCeiling?: BaseCeiling
}

export interface ValidationResult {
  ok: boolean
  violations: string[]
  stage: string
}

export interface DraftResponse {
  draftId: string
  tenantId: string
  authorId: string
  accepted: boolean
  canonicalYaml: string | null
  validation: ValidationResult
}

export interface FixtureCell {
  principalRoles: string[]
  principalTenant: string
  principalAttrs: JsonRecord
  resourceTenant: string
  resourceAttrs: JsonRecord
  action: string
  label: string
}

export interface BundleSnapshot {
  bundleId: string
  policy: JsonRecord | null
  ceiling: BaseCeiling | null
  canonicalContent: string
}

export interface ReviewRequest {
  current: BundleSnapshot
  candidate: BundleSnapshot
  matrix: { cells: FixtureCell[]; fixtureSetHash: string }
  vocabulary: ManifestVocabulary
}

export interface ConsequenceDelta {
  cell: FixtureCell
  from: 'ALLOW' | 'DENY'
  to: 'ALLOW' | 'DENY'
  direction: 'WIDENED' | 'NARROWED'
  overPermission: boolean
  businessConsequence: string
}

export interface ConsequenceReview {
  tenantId: string
  resourceKind: string
  currentBundleId: string
  candidateBundleId: string
  fixtureSetHash: string
  deltas: ConsequenceDelta[]
  overPermissionAlarm: boolean
  principalsGainingAccess: number
  canonicalDelta: string
  consequenceReviewHash: string
  disclosure: { sampledNotFormal: boolean; sampledCellCount: number; statement: string }
  provenance: { sourceId: string; currentBatch: JsonRecord; candidateBatch: JsonRecord }
  generatedAt: string
  displayProse: string | null
}

export interface BundleFile { path: string; yaml: string }
export interface BundleTestMetadata {
  fixtureSetHash: string
  testCount: number
  oracle: string
  pdpSourceId: string
}
export interface PolicyBundle {
  bundleId: string
  tenantId: string
  files: BundleFile[]
  manifestRefs: string[]
  testMetadata: BundleTestMetadata
  canonicalContent: string
}

export interface BundleView {
  bundleId: string
  tenantId: string
  gitCommit: string
  fixtureSetHash: string
  testCount: number
  testOracle: string
  pdpSourceId: string
  createdAt: string
}

export interface PromotionReceipt {
  promotionId: string
  tenantId: string
  fromBundleId: string
  toBundleId: string
  directoryVersion: number
  kind: 'PROMOTION' | 'ROLLBACK'
  idempotentReplay: boolean
}

export interface ReviewHandoff {
  reviewId: string
  authorId: string
  review: ConsequenceReview
  candidate: PolicyBundle
  storedAt: string
}

export interface ExaminerChain {
  transactionId: string
  tenantId: string
  cerbosCallId: string
  activePolicyVersion: string
  decision: string
  resourceKind: string
  action: string
  bundleId: string
  gitCommit: string
  testMetadata: BundleTestMetadata
  approverId: string
  consequenceReviewHash: string
  approvalSignatureValid: boolean
  complete: boolean
}

export interface BreakGlassRequest {
  scope: string
  resourceKind: string
  action: string
  role: string
  ttlMinutes: number
  justification: string
}

export interface BreakGlassGrant {
  grantId: string
  tenantId: string
  requestedBy: string
  admissible: boolean
  issued: boolean
  expiresAt: string
  boundsViolations: string[]
  c2Violations: string[]
}

export const studioApi = {
  createDraft: (payload: DraftRequest) =>
    req<DraftResponse>('POST', '/admin/studio/drafts', payload),
  getVocabulary: (resourceKind: string) =>
    req<StudioGroundingSnapshot>('GET', `/admin/studio/vocabulary/${encodeURIComponent(resourceKind)}`),
  createReview: (payload: ReviewRequest) =>
    req<ConsequenceReview>('POST', '/admin/studio/reviews', payload),
  createAssembledReview: (resourceKind: string, canonicalYaml: string) =>
    req<ConsequenceReview>('POST', '/admin/studio/reviews/assembled', { resourceKind, canonicalYaml }),
  assembleCandidateBundle: (resourceKind: string, canonicalYaml: string, fixtureSetHash: string) =>
    req<PolicyBundle>('POST', '/admin/studio/bundles/candidates', { resourceKind, canonicalYaml, fixtureSetHash }),
  getReview: (reviewId: string) =>
    req<ConsequenceReview>('GET', `/admin/studio/reviews/${encodeURIComponent(reviewId)}`),
  listPendingReviews: () => req<ReviewHandoff[]>('GET', '/admin/studio/reviews/pending'),
  listBundles: () => req<BundleView[]>('GET', '/admin/studio/bundles'),
  getBundle: (bundleId: string) =>
    req<BundleView>('GET', `/admin/studio/bundles/${encodeURIComponent(bundleId)}`),
  getExaminerChain: (cerbosCallId: string) =>
    req<ExaminerChain>('GET', `/admin/studio/examiner/${encodeURIComponent(cerbosCallId)}`),
  promote: (reviewId: string, idempotencyKey: string) =>
    req<PromotionReceipt>('POST', '/admin/studio/promotions', { reviewId, idempotencyKey }),
  rollback: (reviewId: string, idempotencyKey: string) =>
    req<PromotionReceipt>('POST', '/admin/studio/rollbacks', { reviewId, idempotencyKey }),
  requestBreakGlass: (payload: BreakGlassRequest) =>
    req<BreakGlassGrant>('POST', '/admin/studio/break-glass', payload),
  approveBreakGlass: (grantId: string, correlationId?: string) =>
    req<BreakGlassGrant>('POST', `/admin/studio/break-glass/${encodeURIComponent(grantId)}/approve`, undefined,
      correlationId ? { 'X-Correlation-Id': correlationId } : undefined),
  listBreakGlass: () => req<BreakGlassGrant[]>('GET', '/admin/studio/break-glass'),
}
