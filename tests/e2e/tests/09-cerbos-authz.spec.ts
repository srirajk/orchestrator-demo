import { test, expect } from '@playwright/test'

/**
 * Cerbos Authorization Matrix — direct PDP API tests
 *
 * Calls the Cerbos PDP REST API at :3594 (host) for every authorization scenario.
 * These are pure API tests — no browser, no gateway. They validate that the
 * Cerbos policies enforce the correct structural decisions.
 *
 * Three-layer auth architecture:
 * - Layer 1 (IAM/Cerbos structural): can this role perform this action? (these tests)
 * - Layer 2 (Coverage service): does this RM cover this client? (08-domain-authz.spec.ts)
 * - Layer 3 (Gateway enforcement): prune-before-fan-out using layers 1+2
 *
 * Cerbos is role-based only — no book attribute. Book-of-business is the coverage
 * service's responsibility, not Cerbos's.
 */

// Cerbos REST API — note host port is 3594 (mapped from container port 3592)
const CERBOS_URL = process.env.CERBOS_URL || 'http://localhost:3594'
const IAM_URL    = process.env.IAM_URL    || 'http://localhost:8084'

// IAM service passwords — override via env for CI or rotated credentials
const IAM_ADMIN_PASSWORD = process.env.IAM_ADMIN_PASSWORD || 'Meridian@2024'
const IAM_USER_PASSWORD  = process.env.IAM_USER_PASSWORD  || 'Meridian@2024'

// ── Cerbos REST helper ─────────────────────────────────────────────────────

interface Principal {
  id: string
  roles: string[]
  attr: Record<string, unknown>
}

interface Resource {
  kind: string
  id: string
  attr: Record<string, unknown>
}

async function checkResource(
  principal: Principal,
  resource: Resource,
  actions: string[]
): Promise<Record<string, string>> {
  const resp = await fetch(`${CERBOS_URL}/api/check/resources`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      principal,
      resources: [{ resource, actions }],
    }),
  })
  if (!resp.ok) {
    const body = await resp.text()
    throw new Error(`Cerbos returned ${resp.status}: ${body}`)
  }
  const data = await resp.json() as {
    results?: Array<{ resource: { id: string }; actions: Record<string, string> }>
  }
  return data.results?.[0]?.actions ?? {}
}

// ── IAM login helper ────────────────────────────────────────────────────────

async function iamLogin(username: string, password: string): Promise<string> {
  const resp = await fetch(`${IAM_URL}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
  if (!resp.ok) throw new Error(`IAM login failed for ${username}: ${resp.status}`)
  const data = await resp.json() as { accessToken: string }
  return data.accessToken
}

async function iamRequest(token: string, method: string, path: string, body?: unknown): Promise<Response> {
  return fetch(`${IAM_URL}${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`,
    },
    body: body ? JSON.stringify(body) : undefined,
  })
}

// ── Principals used across tests ───────────────────────────────────────────

// Current agent-policy contract: `segments` is a MAP  segment -> the data-classification tier
// the principal holds IN that segment (membership = key present; ceiling = its value). This
// replaced the old flat segment array + numeric clearance. chat_user is the runtime front-door
// role (relationship_manager kept as a policy alias). No book attr — Cerbos is structural only;
// book-of-business is the domain coverage service's job.
const RM_JANE: Principal = {
  id: 'rm_jane',
  roles: ['chat_user'],
  attr: {
    tenant_id: 'default',
    segments: { wealth: 'confidential-pii' },   // full wealth tier; not a servicing member
    admin_domains: [],
  },
}

const JUNIOR_RM: Principal = {
  id: 'junior_rm',
  roles: ['chat_user'],
  attr: {
    tenant_id: 'default',
    segments: { wealth: 'internal' },           // lowest tier — below confidential
    admin_domains: [],
  },
}

const PLATFORM_ADMIN: Principal = {
  id: 'admin',
  roles: ['platform_admin'],
  attr: { tenant_id: 'default', clearance: 3, segments: ['all'], admin_domains: [] },
}

const AUDITOR_SARAH: Principal = {
  id: 'auditor_sarah',
  roles: ['auditor'],
  attr: { tenant_id: 'default', clearance: 2, segments: [], admin_domains: [] },
}

const POLICY_AUTHOR: Principal = {
  id: 'policy_author',
  roles: ['policy_author'],
  attr: { tenant_id: 'default', clearance: 1, segments: [], admin_domains: [] },
}

const POLICY_APPROVER: Principal = {
  id: 'policy_approver',
  roles: ['policy_approver'],
  attr: { tenant_id: 'default', clearance: 3, segments: [], admin_domains: [] },
}

// ── Agent resources ────────────────────────────────────────────────────────

// Resource contract: the policy gates on access_mode ("read"/"write"), the agent's domain
// (mapped to a segment inside policy config), data_classification, and audience.
const PORTFOLIO_ANALYTICS = {
  kind: 'agent',
  id: 'portfolio-analytics',
  attr: {
    domain: 'wealth-management',
    access_mode: 'read',
    audience: 'segment',
    data_classification: 'confidential',
    tenant_id: 'default',
  },
}

const SETTLEMENT_AGENT = {
  kind: 'agent',
  id: 'settlement-status',
  attr: {
    domain: 'asset-servicing',
    access_mode: 'read',
    audience: 'segment',
    data_classification: 'confidential',
    tenant_id: 'default',
  },
}

const TRADE_EXECUTION_AGENT = {
  kind: 'agent',
  id: 'trade-execution',
  attr: {
    domain: 'wealth-management',
    access_mode: 'write',        // mutating — Phase 1 chat_user is read-only, so this DENIES
    audience: 'segment',
    data_classification: 'restricted',
    tenant_id: 'default',
  },
}

// ────────────────────────────────────────────────────────────────────────────
// SUITE 1 — Agent resource (gateway routing gate)
// ────────────────────────────────────────────────────────────────────────────

test.describe('Agent resource authz (Cerbos PDP)', () => {

  test('P1 rm_jane → portfolio-analytics [ALLOW] — happy path', async () => {
    const actions = await checkResource(RM_JANE, PORTFOLIO_ANALYTICS, ['invoke'])
    expect(actions['invoke'], 'rm_jane with wealth segment + clearance 2 must be allowed').toBe('EFFECT_ALLOW')
  })

  test('P1 platform_admin → any agent [ALLOW] — unconditional bypass', async () => {
    const actions = await checkResource(PLATFORM_ADMIN, PORTFOLIO_ANALYTICS, ['invoke', 'register', 'deregister'])
    expect(actions['invoke']).toBe('EFFECT_ALLOW')
    expect(actions['register']).toBe('EFFECT_ALLOW')
    expect(actions['deregister']).toBe('EFFECT_ALLOW')
  })

  test('P1 rm_jane → mutating trade-execution [DENY] — read-only gate', async () => {
    const actions = await checkResource(RM_JANE, TRADE_EXECUTION_AGENT, ['invoke'])
    expect(actions['invoke'], 'relationship_manager must never invoke mutating agents').toBe('EFFECT_DENY')
  })

  test('P3 junior_rm (clearance 1) → confidential portfolio-analytics [DENY] — Gap#1 fixed', async () => {
    // This test validates the critical clearance fix: junior_rm has clearance=1 (internal)
    // and portfolio-analytics requires data_classification=confidential (clearance >= 2).
    // With the old broken CEL this returned ALLOW. Now it must return DENY.
    const actions = await checkResource(JUNIOR_RM, PORTFOLIO_ANALYTICS, ['invoke'])
    expect(actions['invoke'], 'junior_rm clearance 1 must not access confidential agents').toBe('EFFECT_DENY')
  })

  test('P3 junior_rm (clearance 1) → internal agent [ALLOW] — clearance tier ladder', async () => {
    const internalAgent = {
      kind: 'agent',
      id: 'nav-lookup',
      attr: {
        domain: 'wealth-management',
        access_mode: 'read',
        audience: 'segment',
        data_classification: 'internal',
        tenant_id: 'default',
      },
    }
    const actions = await checkResource(JUNIOR_RM, internalAgent, ['invoke'])
    expect(actions['invoke'], 'junior_rm with clearance 1 may invoke internal agents').toBe('EFFECT_ALLOW')
  })

  test('rm_jane → asset-servicing agent [DENY] — wrong segment', async () => {
    // rm_jane is in wealth segment, not servicing — should be denied servicing agents
    const actions = await checkResource(RM_JANE, SETTLEMENT_AGENT, ['invoke'])
    expect(actions['invoke'], 'wealth-segment RM must not invoke asset-servicing agents').toBe('EFFECT_DENY')
  })

})

// ────────────────────────────────────────────────────────────────────────────
// SUITE 2 — Relationship resource (structural role-based gate via Cerbos)
//
// Architecture note: Cerbos enforces STRUCTURAL access (can RM role read
// relationship resources?). Book-of-business enforcement (does rm_jane actually
// cover this client?) is handled by the domain coverage service (DISCOVER/CHECK),
// NOT by Cerbos. See 08-domain-authz.spec.ts for coverage service E2E tests.
// ────────────────────────────────────────────────────────────────────────────

test.describe('Relationship resource authz — structural role gate (Cerbos PDP)', () => {

  test('rm_jane → REL-00042 Whitman [ALLOW] — relationship_manager role', async () => {
    const whitman = { kind: 'relationship', id: 'REL-00042', attr: { tenant_id: 'default' } }
    const actions = await checkResource(RM_JANE, whitman, ['read'])
    expect(actions['read'], 'relationship_manager role → ALLOW for any relationship').toBe('EFFECT_ALLOW')
  })

  test('rm_jane → REL-00188 Okafor [ALLOW] — Cerbos structural gate (coverage service denies later)', async () => {
    // Cerbos structural check: rm_jane has relationship_manager role → ALLOW.
    // The actual denial for Okafor comes from the coverage service (not-in-book),
    // which runs inside the gateway at fan-out time. See 08-domain-authz.spec.ts.
    const okafor = { kind: 'relationship', id: 'REL-00188', attr: { tenant_id: 'default' } }
    const actions = await checkResource(RM_JANE, okafor, ['read'])
    expect(actions['read'], 'Cerbos structural gate passes; coverage service is the book-of-business authority').toBe('EFFECT_ALLOW')
  })

  test('rm_jane → REL-00099 Calderon [ALLOW] — relationship_manager role', async () => {
    const calderon = { kind: 'relationship', id: 'REL-00099', attr: { tenant_id: 'default' } }
    const actions = await checkResource(RM_JANE, calderon, ['read'])
    expect(actions['read']).toBe('EFFECT_ALLOW')
  })

  test('platform_admin → REL-00188 Okafor [ALLOW] — admin sees all', async () => {
    const okafor = { kind: 'relationship', id: 'REL-00188', attr: { tenant_id: 'default' } }
    const actions = await checkResource(PLATFORM_ADMIN, okafor, ['read'])
    expect(actions['read']).toBe('EFFECT_ALLOW')
  })

  test('junior_rm → REL-00042 Whitman [ALLOW] — structural gate passes (coverage service handles book)', async () => {
    // Cerbos structural check: junior_rm has relationship_manager role → ALLOW.
    // Coverage service (not Cerbos) would deny based on book-of-business.
    const whitman = { kind: 'relationship', id: 'REL-00042', attr: { tenant_id: 'default' } }
    const actions = await checkResource(JUNIOR_RM, whitman, ['read'])
    expect(actions['read'], 'Cerbos structural gate passes for relationship_manager').toBe('EFFECT_ALLOW')
  })

})

// ────────────────────────────────────────────────────────────────────────────
// SUITE 3 — IAM resource (admin-ui authorization matrix)
// ────────────────────────────────────────────────────────────────────────────

test.describe('IAM resource authz — RBAC matrix (Cerbos PDP)', () => {

  const auditLogResource = {
    kind: 'iam-resource',
    id: 'audit-log',
    attr: { resource_type: 'audit_log', tenant_id: 'default', owner_id: '' },
  }

  const policyResource = {
    kind: 'iam-resource',
    id: 'wealth-agent-policy',
    attr: { resource_type: 'policy', tenant_id: 'default', owner_id: '' },
  }

  const userResource = {
    kind: 'iam-resource',
    id: 'new-user',
    attr: { resource_type: 'user', tenant_id: 'default', owner_id: '' },
  }

  test('P2 auditor_sarah → read audit_log [ALLOW] — same_tenant + auditor role', async () => {
    const actions = await checkResource(AUDITOR_SARAH, auditLogResource, ['read', 'list', 'export'])
    expect(actions['read'], 'auditor must be allowed to read audit logs').toBe('EFFECT_ALLOW')
    expect(actions['list']).toBe('EFFECT_ALLOW')
    expect(actions['export']).toBe('EFFECT_ALLOW')
  })

  test('P2 auditor_sarah → create user [DENY] — auditor is read-only', async () => {
    const actions = await checkResource(AUDITOR_SARAH, userResource, ['create'])
    expect(actions['create'], 'auditor must not create users').toBe('EFFECT_DENY')
  })

  test('auditor_sarah → read policy [DENY] — auditor only accesses audit_log', async () => {
    const actions = await checkResource(AUDITOR_SARAH, policyResource, ['read'])
    expect(actions['read'], 'auditor can only read audit_log, not policies').toBe('EFFECT_DENY')
  })

  test('P2 policy_author → create policy (draft) [ALLOW] — policy_drafter derived role', async () => {
    const actions = await checkResource(POLICY_AUTHOR, policyResource, ['create', 'read', 'list'])
    expect(actions['create'], 'policy_author must create policy drafts').toBe('EFFECT_ALLOW')
    expect(actions['read']).toBe('EFFECT_ALLOW')
    expect(actions['list']).toBe('EFFECT_ALLOW')
  })

  test('P2 policy_author → deploy_policy [DENY] — separation of duties', async () => {
    // policy_author can DRAFT but must not DEPLOY — the approver is a separate human
    const actions = await checkResource(POLICY_AUTHOR, policyResource, ['deploy_policy', 'approve_policy'])
    expect(actions['deploy_policy'], 'policy_author must NOT deploy — separation of duties').toBe('EFFECT_DENY')
    expect(actions['approve_policy'], 'policy_author must NOT approve — separation of duties').toBe('EFFECT_DENY')
  })

  test('P2 policy_approver → deploy_policy [ALLOW] — policy_approver_role derived role', async () => {
    const actions = await checkResource(POLICY_APPROVER, policyResource, ['approve_policy', 'deploy_policy', 'read'])
    expect(actions['approve_policy'], 'policy_approver must be able to approve').toBe('EFFECT_ALLOW')
    expect(actions['deploy_policy'], 'policy_approver must be able to deploy').toBe('EFFECT_ALLOW')
    expect(actions['read']).toBe('EFFECT_ALLOW')
  })

  test('policy_approver → create user [DENY] — policy role has no user management access', async () => {
    const actions = await checkResource(POLICY_APPROVER, userResource, ['create', 'delete'])
    expect(actions['create']).toBe('EFFECT_DENY')
    expect(actions['delete']).toBe('EFFECT_DENY')
  })

  test('platform_admin → all IAM actions [ALLOW] — unrestricted', async () => {
    const actions = await checkResource(PLATFORM_ADMIN, userResource,
      ['create', 'read', 'update', 'delete', 'list'])
    for (const [action, effect] of Object.entries(actions)) {
      expect(effect, `platform_admin must be allowed: ${action}`).toBe('EFFECT_ALLOW')
    }
  })

})

// ────────────────────────────────────────────────────────────────────────────
// SUITE 4 — IAM service API integration (real JWT, real endpoints)
// ────────────────────────────────────────────────────────────────────────────

test.describe('IAM service API — authorization enforced end-to-end', () => {

  test('admin can list all users (paginated)', async () => {
    const token = await iamLogin('admin', IAM_ADMIN_PASSWORD)
    const resp = await iamRequest(token, 'GET', '/users')
    expect(resp.status).toBe(200)
    const page = await resp.json() as { content: unknown[]; totalElements: number }
    expect(Array.isArray(page.content)).toBe(true)
    expect(page.totalElements).toBeGreaterThanOrEqual(3) // at least admin, rm_jane, auditor_user
  })

  test('rm_jane can read her own profile', async () => {
    const token = await iamLogin('rm_jane', IAM_USER_PASSWORD)
    const resp = await iamRequest(token, 'GET', '/users/rm_jane')
    expect(resp.status).toBe(200)
    const user = await resp.json() as { id: string; roles: string[] }
    expect(user.id).toBe('rm_jane')
  })

  test('rm_jane cannot list all users — @PreAuthorize enforces admin-only', async () => {
    const token = await iamLogin('rm_jane', IAM_USER_PASSWORD)
    const resp = await iamRequest(token, 'GET', '/users')
    // relationship_manager has no admin role — Spring Security returns 403
    expect(resp.status).toBe(403)
  })

  test('admin can read audit log', async () => {
    const token = await iamLogin('admin', IAM_ADMIN_PASSWORD)
    const resp = await iamRequest(token, 'GET', '/admin/audit?page=0&size=5')
    expect(resp.status).toBe(200)
    const body = await resp.json() as { content: unknown[] }
    expect(body.content).toBeDefined()
  })

  test('policy lifecycle: draft → approve → deploy (via API)', async () => {
    const token = await iamLogin('admin', IAM_ADMIN_PASSWORD)

    // Create a draft policy
    const draft = {
      name: `e2e-test-policy-${Date.now()}`,
      resourceType: 'agent',
      content: `apiVersion: api.cerbos.dev/v1\nresourcePolicy:\n  version: "default"\n  resource: agent\n  rules: []`,
    }
    const createResp = await iamRequest(token, 'POST', '/admin/policies', draft)
    expect(createResp.status, 'Create policy draft').toBe(201)
    const created = await createResp.json() as { id: string; status: string; name: string }
    expect(created.status).toBe('draft')

    // Approve it (PUT /status)
    const approveResp = await iamRequest(token, 'PUT', `/admin/policies/${created.id}/status`, { status: 'approved' })
    expect(approveResp.status, 'Approve policy').toBe(200)
    const approved = await approveResp.json() as { status: string }
    expect(approved.status).toBe('approved')

    // Deploy it (PUT /status)
    const deployResp = await iamRequest(token, 'PUT', `/admin/policies/${created.id}/status`, { status: 'deployed' })
    expect(deployResp.status, 'Deploy policy').toBe(200)
    const deployed = await deployResp.json() as { status: string }
    expect(deployed.status).toBe('deployed')

    // Audit log should now contain entries for this policy
    const auditResp = await iamRequest(token, 'GET', `/admin/audit?page=0&size=20`)
    const auditBody = await auditResp.json() as { content: Array<{ resourceId: string; action: string }> }
    const policyEvents = auditBody.content.filter(e => e.resourceId === created.id)
    expect(policyEvents.length).toBeGreaterThanOrEqual(1)
  })

  test('relationship book seeded correctly — rm_jane has Whitman + Calderon', async () => {
    const token = await iamLogin('admin', IAM_ADMIN_PASSWORD)
    const resp = await iamRequest(token, 'GET', '/users/rm_jane')
    expect(resp.status).toBe(200)
    const user = await resp.json() as { book: string[] }
    expect(user.book).toContain('REL-00042')  // Whitman
    expect(user.book).toContain('REL-00099')  // Calderon
    expect(user.book).not.toContain('REL-00188')  // Okafor — must NOT be in her book
  })

  test('rm_carlos has Okafor in book, rm_jane does not', async () => {
    const token = await iamLogin('admin', IAM_ADMIN_PASSWORD)

    const janeResp = await iamRequest(token, 'GET', '/users/rm_jane')
    const janeUser = await janeResp.json() as { book: string[] }
    expect(janeUser.book).not.toContain('REL-00188')

    const carlosResp = await iamRequest(token, 'GET', '/users/rm_carlos')
    const carlosUser = await carlosResp.json() as { book: string[] }
    expect(carlosUser.book).toContain('REL-00188')
  })

})

// ────────────────────────────────────────────────────────────────────────────
// SUITE 5 — Cross-tenant isolation
// ────────────────────────────────────────────────────────────────────────────

test.describe('Cross-tenant isolation (Cerbos PDP)', () => {

  test('principal from tenant-A cannot access resource in tenant-B', async () => {
    const principalTenantA: Principal = {
      id: 'user-a',
      roles: ['tenant_admin'],
      attr: { tenant_id: 'tenant-a', clearance: 2 },
    }
    const resourceTenantB = {
      kind: 'iam-resource',
      id: 'some-resource',
      attr: { resource_type: 'user', tenant_id: 'tenant-b', owner_id: '' },
    }
    const actions = await checkResource(principalTenantA, resourceTenantB, ['read', 'create'])
    expect(actions['read'], 'tenant-A admin must not read tenant-B resources').toBe('EFFECT_DENY')
    expect(actions['create']).toBe('EFFECT_DENY')
  })

  test('same-tenant principal CAN access same-tenant resource', async () => {
    const principal: Principal = {
      id: 'user-a',
      roles: ['tenant_admin'],
      attr: { tenant_id: 'tenant-x', clearance: 2 },
    }
    const resource = {
      kind: 'iam-resource',
      id: 'some-resource',
      attr: { resource_type: 'user', tenant_id: 'tenant-x', owner_id: '' },
    }
    const actions = await checkResource(principal, resource, ['read'])
    expect(actions['read'], 'same-tenant admin must read same-tenant resources').toBe('EFFECT_ALLOW')
  })

})
