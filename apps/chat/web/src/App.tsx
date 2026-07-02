import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { ErrorBoundary } from './components/ErrorBoundary'
import { AuthGate } from './components/AuthGate'
import { Layout } from './components/Layout'
import { ChatPane } from './components/ChatPane'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: (failureCount, error) => {
        // Don't retry on 401
        if ((error as Error & { status?: number })?.status === 401) return false
        return failureCount < 2
      },
    },
  },
})

export default function App() {
  return (
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <AuthGate>
            <Routes>
              <Route element={<Layout />}>
                <Route path="/" element={<Navigate to="/c/new" replace />} />
                <Route path="/c/new" element={<ChatPane />} />
                <Route path="/c/:id" element={<ChatPane />} />
                <Route path="*" element={<Navigate to="/c/new" replace />} />
              </Route>
            </Routes>
          </AuthGate>
        </BrowserRouter>
      </QueryClientProvider>
    </ErrorBoundary>
  )
}
