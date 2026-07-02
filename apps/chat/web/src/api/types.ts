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
}

export interface Message {
  id: string
  role: 'user' | 'assistant' | 'system'
  content: string
  createdAt: string
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
