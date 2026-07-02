import { Schema, model, Types } from 'mongoose';

export interface IConversation {
  _id: Types.ObjectId;
  userId: string;
  title: string;
  projectId?: string;
  summary?: string;
  archived?: boolean;
  createdAt: Date;
  updatedAt: Date;
}

const conversationSchema = new Schema<IConversation>(
  {
    userId: { type: String, required: true, index: true },
    title: { type: String, required: true, default: 'New Conversation' },
    projectId: { type: String, index: true },
    summary: { type: String },
    archived: { type: Boolean, default: false },
  },
  { timestamps: true },
);

export const Conversation = model<IConversation>('Conversation', conversationSchema);
