import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { AuthProvider, useAuth, hasAdminRole } from './hooks/useAuth'
import { ToastProvider } from './components/ui/Toast'
import { ErrorBoundary } from './components/ErrorBoundary'
import { Layout } from './components/Layout'
import { Login } from './pages/Login'
import { Dashboard } from './pages/Dashboard'
import { Users } from './pages/Users'
import { Teams } from './pages/Teams'
import { Roles } from './pages/Roles'
import { Policies } from './pages/Policies'
import { AuditLog } from './pages/AuditLog'

function Protected({ children }: { children: React.ReactNode }) {
  const { token, user, logout } = useAuth()
  // Authenticated is not enough — the admin console requires an admin role. A relationship manager
  // (chat_user) holds a valid token but must never reach these surfaces. Bounce non-admins back to
  // login; clearing the session avoids a redirect loop with a valid-but-unauthorized token.
  if (!token || !user) return <Navigate to="/login" replace />
  if (!hasAdminRole(user)) {
    logout()
    return <Navigate to="/login" replace state={{ denied: true }} />
  }
  return <>{children}</>
}

function PageBoundary({ children }: { children: React.ReactNode }) {
  return <ErrorBoundary>{children}</ErrorBoundary>
}

function App() {
  return (
    <AuthProvider>
      <ToastProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/login" element={<Login />} />
            <Route element={<Protected><Layout /></Protected>}>
              <Route index element={<PageBoundary><Dashboard /></PageBoundary>} />
              <Route path="users"    element={<PageBoundary><Users /></PageBoundary>} />
              <Route path="teams"    element={<PageBoundary><Teams /></PageBoundary>} />
              <Route path="roles"    element={<PageBoundary><Roles /></PageBoundary>} />
              <Route path="policies" element={<PageBoundary><Policies /></PageBoundary>} />
              <Route path="audit"    element={<PageBoundary><AuditLog /></PageBoundary>} />
            </Route>
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </BrowserRouter>
      </ToastProvider>
    </AuthProvider>
  )
}

export default App
