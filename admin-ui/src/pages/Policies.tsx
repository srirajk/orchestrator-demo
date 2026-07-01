import { useState } from 'react'
import { useQuery, useMutation } from '@tanstack/react-query'
import { FileText, Sparkles, CheckCircle, XCircle, Upload, ChevronDown, ChevronRight, AlertTriangle } from 'lucide-react'
import { policiesApi, rolesApi, adminApi, type PolicyIntent } from '../api/client'
import { Button } from '../components/ui/Button'
import { Input, Textarea, Select } from '../components/ui/Input'
import { Badge } from '../components/ui/Badge'
import { useToast } from '../components/ui/Toast'

const ACTIONS_MAP: Record<string, string[]> = {
  agent:        ['invoke', 'register', 'deregister'],
  relationship: ['read', 'update', 'delete'],
  domain:       ['read', 'admin', 'add_member', 'remove_member'],
}

const EMPTY_INTENT: PolicyIntent = {
  resource: 'agent',
  subject_roles: [],
  actions: [],
  conditions: {},
  policy_name: '',
  description: '',
}

export function Policies() {
  const { toast } = useToast()
  const [intent, setIntent] = useState<PolicyIntent>({ ...EMPTY_INTENT })
  const [generatedYaml, setGeneratedYaml] = useState('')
  const [explanation, setExplanation]     = useState('')
  const [warnings, setWarnings]           = useState<string[]>([])
  const [validResult, setValidResult]     = useState<{ valid: boolean; errors: string[] } | null>(null)
  const [expandedPolicy, setExpandedPolicy] = useState<string | null>(null)

  const { data: polRes, refetch } = useQuery({ queryKey: ['policies'], queryFn: policiesApi.list })
  const { data: roles = [] } = useQuery({ queryKey: ['roles'], queryFn: rolesApi.list })
  const { data: resourcesData } = useQuery({ queryKey: ['policy-resources'], queryFn: adminApi.policyResources })
  const { data: segmentsData } = useQuery({ queryKey: ['segments'], queryFn: adminApi.segments })

  const policies = polRes?.policies ?? []
  const RESOURCES = resourcesData?.resources ?? ['agent', 'relationship', 'domain']
  const SEGMENTS = segmentsData?.segments ?? ['wealth', 'servicing']
  const actions = ACTIONS_MAP[intent.resource] ?? []

  const generateMut = useMutation({
    mutationFn: () => policiesApi.generate(intent),
    onSuccess: (res) => {
      setGeneratedYaml(res.yaml)
      setExplanation(res.explanation)
      setWarnings(res.warnings)
      setValidResult({ valid: res.valid, errors: res.errors })
    },
    onError: (e: Error) => toast('error', e.message),
  })

  const validateMut = useMutation({
    mutationFn: () => policiesApi.validate(generatedYaml),
    onSuccess: (res) => setValidResult(res),
    onError: (e: Error) => toast('error', e.message),
  })

  const applyMut = useMutation({
    mutationFn: () => policiesApi.apply(generatedYaml, intent.policy_name || 'generated_policy'),
    onSuccess: () => { refetch(); toast('success', 'Policy applied — Cerbos will hot-reload') },
    onError: (e: Error) => toast('error', e.message),
  })

  function toggleAction(action: string) {
    setIntent(i => ({
      ...i,
      actions: i.actions.includes(action) ? i.actions.filter(a => a !== action) : [...i.actions, action],
    }))
  }

  function toggleRole(roleId: string) {
    setIntent(i => ({
      ...i,
      subject_roles: i.subject_roles.includes(roleId) ? i.subject_roles.filter(r => r !== roleId) : [...i.subject_roles, roleId],
    }))
  }

  function toggleSegment(seg: string) {
    const segs = intent.conditions.segments ?? []
    setIntent(i => ({
      ...i,
      conditions: {
        ...i.conditions,
        segments: segs.includes(seg) ? segs.filter(s => s !== seg) : [...segs, seg],
      },
    }))
  }

  return (
    <div className="px-8 py-8 max-w-6xl">
      <div className="mb-6">
        <h1 className="text-xl font-bold text-slate-900">Policies</h1>
        <p className="text-sm text-slate-500 mt-0.5">Manage Cerbos authorization policies</p>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Left: existing policies */}
        <div>
          <h2 className="text-sm font-semibold text-slate-700 mb-3">Active Policies ({policies.length})</h2>
          <div className="space-y-2">
            {policies.length === 0 ? (
              <div className="bg-white rounded-xl border border-slate-200 py-10 text-center text-sm text-slate-400">No policies found</div>
            ) : policies.map(p => (
              <div key={p.filename} className="bg-white rounded-lg border border-slate-200 overflow-hidden">
                <button
                  className="w-full flex items-center justify-between px-4 py-3 hover:bg-slate-50 transition-colors text-left"
                  onClick={() => setExpandedPolicy(expandedPolicy === p.filename ? null : p.filename)}
                >
                  <div className="flex items-center gap-3">
                    <FileText size={15} className="text-slate-400 shrink-0" />
                    <div>
                      <p className="text-sm font-medium text-slate-900">{p.filename}</p>
                      <div className="flex gap-1.5 mt-0.5">
                        <Badge color="slate">{p.policy_type}</Badge>
                        {p.resource !== '—' && <Badge color="blue">{p.resource}</Badge>}
                      </div>
                    </div>
                  </div>
                  {expandedPolicy === p.filename ? <ChevronDown size={14} className="text-slate-400" /> : <ChevronRight size={14} className="text-slate-400" />}
                </button>
                {expandedPolicy === p.filename && p.content && (
                  <div className="border-t border-slate-100 px-4 py-3 bg-slate-50">
                    <pre className="text-xs text-slate-700 overflow-x-auto whitespace-pre-wrap font-mono">{p.content}</pre>
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>

        {/* Right: generator */}
        <div>
          <h2 className="text-sm font-semibold text-slate-700 mb-3 flex items-center gap-1.5">
            <Sparkles size={14} className="text-brand-500" /> AI Policy Generator
          </h2>
          <div className="bg-white rounded-xl border border-slate-200 p-5 space-y-4">
            {/* Resource */}
            <Select label="Resource" value={intent.resource}
              onChange={e => setIntent(i => ({ ...i, resource: e.target.value, actions: [] }))}>
              {RESOURCES.map(r => <option key={r} value={r}>{r}</option>)}
            </Select>

            {/* Roles */}
            <div>
              <label className="text-sm font-medium text-slate-700 block mb-1.5">Subject roles</label>
              <div className="flex flex-wrap gap-2">
                {roles.map(r => (
                  <label key={r.id} className="flex items-center gap-1.5 text-sm cursor-pointer">
                    <input type="checkbox" checked={intent.subject_roles.includes(r.id)} onChange={() => toggleRole(r.id)}
                      className="rounded border-slate-300 text-brand-600 focus:ring-brand-500" />
                    <span>{r.name}</span>
                  </label>
                ))}
              </div>
            </div>

            {/* Actions */}
            <div>
              <label className="text-sm font-medium text-slate-700 block mb-1.5">Actions to allow</label>
              <div className="flex flex-wrap gap-2">
                {actions.map(a => (
                  <label key={a} className="flex items-center gap-1.5 text-sm cursor-pointer">
                    <input type="checkbox" checked={intent.actions.includes(a)} onChange={() => toggleAction(a)}
                      className="rounded border-slate-300 text-brand-600 focus:ring-brand-500" />
                    <code className="text-xs">{a}</code>
                  </label>
                ))}
              </div>
            </div>

            {/* Conditions */}
            <div className="border border-slate-200 rounded-lg p-3 space-y-3">
              <p className="text-xs font-medium text-slate-500 uppercase tracking-wide">Conditions (optional)</p>
              <div className="flex items-center gap-3">
                <label className="text-sm text-slate-700 w-28 shrink-0">Min. clearance</label>
                <input type="range" min={0} max={5} value={intent.conditions.clearance_min ?? 0}
                  onChange={e => setIntent(i => ({ ...i, conditions: { ...i.conditions, clearance_min: Number(e.target.value) || undefined } }))}
                  className="flex-1 accent-brand-600" />
                <span className="text-sm font-medium text-slate-700 w-6">{intent.conditions.clearance_min || 'Any'}</span>
              </div>
              <div className="flex items-center gap-3">
                <label className="text-sm text-slate-700 w-28 shrink-0">Segments</label>
                <div className="flex gap-2">
                  {SEGMENTS.map(s => (
                    <label key={s} className="flex items-center gap-1 text-sm cursor-pointer">
                      <input type="checkbox" checked={(intent.conditions.segments ?? []).includes(s)} onChange={() => toggleSegment(s)}
                        className="rounded border-slate-300 text-brand-600 focus:ring-brand-500" />
                      {s}
                    </label>
                  ))}
                </div>
              </div>
              <label className="flex items-center gap-2 text-sm cursor-pointer">
                <input type="checkbox" checked={!!intent.conditions.non_mutating_only}
                  onChange={e => setIntent(i => ({ ...i, conditions: { ...i.conditions, non_mutating_only: e.target.checked || undefined } }))}
                  className="rounded border-slate-300 text-brand-600 focus:ring-brand-500" />
                Non-mutating (read-only) only
              </label>
              <Input
                label="Custom CEL expression"
                placeholder='P.attr.clearance >= 3 && R.attr.domain == "wealth-management"'
                value={intent.conditions.custom_cel ?? ''}
                onChange={e => setIntent(i => ({ ...i, conditions: { ...i.conditions, custom_cel: e.target.value || undefined } }))}
              />
            </div>

            <Input label="Policy name" placeholder="rm-wealth-invoke" value={intent.policy_name}
              onChange={e => setIntent(i => ({ ...i, policy_name: e.target.value }))}
              hint="Filename stem (no .yaml)" />

            <Textarea label="Description" placeholder="Senior RMs in wealth segment can invoke non-mutating wealth-management agents"
              value={intent.description} onChange={e => setIntent(i => ({ ...i, description: e.target.value }))}
              rows={2} />

            <Button className="w-full" loading={generateMut.isPending} onClick={() => generateMut.mutate()}>
              <Sparkles size={14} /> Generate policy
            </Button>
          </div>

          {/* Result */}
          {generatedYaml && (
            <div className="mt-4 bg-white rounded-xl border border-slate-200 p-5 space-y-4">
              {/* Explanation */}
              {explanation && (
                <div className="bg-brand-50 border border-brand-100 rounded-lg p-3">
                  <p className="text-xs font-medium text-brand-700 mb-1">What this policy does</p>
                  <p className="text-sm text-brand-800">{explanation}</p>
                </div>
              )}

              {/* Warnings */}
              {warnings.length > 0 && (
                <div className="bg-amber-50 border border-amber-200 rounded-lg p-3 space-y-1">
                  <div className="flex items-center gap-1.5 text-amber-700 text-xs font-medium mb-1">
                    <AlertTriangle size={13} /> Warnings
                  </div>
                  {warnings.map((w, i) => <p key={i} className="text-xs text-amber-700">{w}</p>)}
                </div>
              )}

              {/* YAML */}
              <div>
                <p className="text-xs font-medium text-slate-500 mb-2">Generated YAML</p>
                <pre className="bg-slate-950 text-slate-100 text-xs rounded-lg p-4 overflow-x-auto font-mono whitespace-pre-wrap">{generatedYaml}</pre>
              </div>

              {/* Validation result */}
              {validResult && (
                <div className={`flex items-start gap-2 p-3 rounded-lg text-sm ${validResult.valid ? 'bg-green-50 text-green-700' : 'bg-red-50 text-red-700'}`}>
                  {validResult.valid
                    ? <CheckCircle size={15} className="mt-0.5 shrink-0" />
                    : <XCircle size={15} className="mt-0.5 shrink-0" />}
                  <div>
                    <p className="font-medium">{validResult.valid ? 'Valid Cerbos policy' : 'Validation errors'}</p>
                    {validResult.errors.map((e, i) => <p key={i} className="text-xs mt-0.5">{e}</p>)}
                  </div>
                </div>
              )}

              <div className="flex gap-2">
                <Button variant="secondary" size="sm" loading={validateMut.isPending} onClick={() => validateMut.mutate()}>
                  <CheckCircle size={13} /> Validate
                </Button>
                <Button size="sm" loading={applyMut.isPending}
                  disabled={!validResult?.valid}
                  onClick={() => applyMut.mutate()}>
                  <Upload size={13} /> Apply to Cerbos
                </Button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
