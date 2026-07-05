export interface User {
  id: string
  username: string
  email: string
  roles: string[]
}

export interface Conversation {
  id: string
  title: string
  projectId?: string
  updatedAt: string
  archived?: boolean
}

export interface MessageClientTiming {
  ttftMs?: number
  totalMs?: number
}

export interface Message {
  id: string
  role: 'user' | 'assistant' | 'system'
  content: string
  createdAt: string
  clientTiming?: MessageClientTiming
}

export interface ConversationDetail {
  conversation: Conversation
  messages: Message[]
}

export interface Project {
  id: string
  name: string
  color: string
}
