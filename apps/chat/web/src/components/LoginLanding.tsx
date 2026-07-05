import { ArrowRight, LockKeyhole, ShieldCheck } from 'lucide-react'
import { redirectToLogin } from '../api/client'

export function LoginLanding() {
  return (
    <div className="min-h-screen bg-[#0B1220] text-white">
      <div className="absolute inset-0 bg-[linear-gradient(rgba(255,255,255,0.035)_1px,transparent_1px),linear-gradient(90deg,rgba(255,255,255,0.035)_1px,transparent_1px)] bg-[size:44px_44px]" />

      <main className="relative flex min-h-screen items-center justify-center px-5 py-10">
        <section
          className="w-full max-w-[460px] rounded-lg border border-white/10 bg-[#131C2E] p-7 shadow-2xl shadow-black/30 sm:p-8"
          aria-labelledby="conduit-login-title"
        >
          <div className="mb-8 flex items-center gap-3">
            <div className="flex h-11 w-11 items-center justify-center rounded-md bg-[#F0C45A] text-base font-extrabold text-[#0B1220] shadow-lg shadow-[#F0C45A]/20">
              C
            </div>
            <div>
              <p className="text-lg font-semibold leading-tight">Conduit</p>
              <p className="text-sm text-slate-400">Enterprise AI Gateway</p>
            </div>
          </div>

          <div className="mb-7 space-y-3">
            <p className="text-xs font-semibold uppercase tracking-[0.18em] text-[#F0C45A]">
              Meridian Private Banking
            </p>
            <h1 id="conduit-login-title" className="text-3xl font-semibold leading-tight tracking-normal text-white">
              Sign in to Conduit
            </h1>
            <p className="text-sm leading-6 text-slate-300">
              Continue through Axiom single sign-on to access authorized client intelligence and chat workflows.
            </p>
          </div>

          <button
            type="button"
            onClick={redirectToLogin}
            className="flex h-12 w-full items-center justify-center gap-2 rounded-md bg-[#F0C45A] px-4 text-sm font-bold text-[#0B1220] shadow-lg shadow-[#F0C45A]/20 transition hover:bg-[#f5d37b] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#F0C45A] focus-visible:ring-offset-2 focus-visible:ring-offset-[#131C2E]"
          >
            Sign in with SSO
            <ArrowRight size={17} aria-hidden="true" />
          </button>

          <div className="mt-7 grid gap-3 border-t border-white/10 pt-6 text-sm text-slate-300 sm:grid-cols-2">
            <div className="flex items-center gap-2">
              <ShieldCheck size={16} className="text-[#F0C45A]" aria-hidden="true" />
              <span>Policy enforced</span>
            </div>
            <div className="flex items-center gap-2">
              <LockKeyhole size={16} className="text-[#F0C45A]" aria-hidden="true" />
              <span>Axiom secured</span>
            </div>
          </div>
        </section>
      </main>
    </div>
  )
}
