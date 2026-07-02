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

// Expose `id` (string) instead of Mongo `_id`/`__v` in JSON responses — the web client reads `id`.
conversationSchema.set('toJSON', {
  virtuals: true,
  versionKey: false,
  transform: (_doc, ret) => { delete (ret as { _id?: unknown })._id; },
});

export const Conversation = model<IConversation>('Conversation', conversationSchema);
