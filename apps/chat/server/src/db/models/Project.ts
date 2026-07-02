import { Schema, model, Types } from 'mongoose';

export interface IProject {
  _id: Types.ObjectId;
  userId: string;
  name: string;
  color?: string;
  createdAt: Date;
  updatedAt: Date;
}

const projectSchema = new Schema<IProject>(
  {
    userId: { type: String, required: true, index: true },
    name: { type: String, required: true },
    color: { type: String },
  },
  { timestamps: true },
);

export const Project = model<IProject>('Project', projectSchema);
