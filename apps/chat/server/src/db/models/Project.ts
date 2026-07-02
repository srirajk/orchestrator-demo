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

projectSchema.set("toJSON", { virtuals: true, versionKey: false, transform: (_doc, ret) => { delete (ret as { _id?: unknown })._id; } });

export const Project = model<IProject>('Project', projectSchema);
