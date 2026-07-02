import mongoose from 'mongoose';
import { config } from '../config';

export async function connectDb(): Promise<void> {
  mongoose.connection.on('error', (err) => {
    console.error('[mongo] connection error:', err);
  });

  mongoose.connection.once('open', () => {
    console.log('[mongo] connected to', config.mongodbUri);
  });

  await mongoose.connect(config.mongodbUri);
}
