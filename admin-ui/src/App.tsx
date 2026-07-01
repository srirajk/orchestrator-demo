import { lazy, Suspense } from 'react'
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { AuthProvider, useAuth } from './hooks/useAuth'
import { ToastProvider } from './components/ui/Toast'
import { Layout } from './components/Layout'
import { Login } from './pages/Login'
import { Dashboard } from './pages/Dashboard'
import { Users } from './pages/Users'
import { Teams } from './pages/Teams'
import { Roles } from './pages/Roles'
import { Policies } from './pages/Policies'
import { AuditLog } from './pages/AuditLog'

const Workbench = lazy(() => import('./pages/Workbench').then((module) => ({ default: module.Workbench })))

function Protected({ children }: { children: React.ReactNode }) {
  const { token } = useAuth()
  return token ? <>{children}</> : <Navigate to="/login" replace />
}

function RouteFallback() {
  return (
    <div className="px-8 py-8">
      <div className="h-5 w-40 rounded bg-slate-200 animate-pulse" />
      <div className="mt-4 h-32 max-w-3xl rounded-lg border border-slate-200 bg-white" />
    </div>
  )
}

function App() {
  return (
    <AuthProvider>
      <ToastProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/login" element={<Login />} />
            <Route element={<Protected><Layout /></Protected>}>
              <Route index element={<Dashboard />} />
              <Route path="users"    element={<Users />} />
              <Route path="teams"    element={<Teams />} />
              <Route path="roles"    element={<Roles />} />
              <Route path="policies" element={<Policies />} />
              <Route path="audit"    element={<AuditLog />} />
              <Route path="workbench" element={<Suspense fallback={<RouteFallback />}><Workbench /></Suspense>} />
            </Route>
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </BrowserRouter>
      </ToastProvider>
    </AuthProvider>
  )
}

export default App
