/**
 * POST /api/files — multipart file upload.
 *
 * STUBBED: MinIO integration is NOT implemented.
 *
 * The route accepts a multipart/form-data request with a single `file` field,
 * buffers it in memory via multer, and returns a placeholder response.
 *
 * To wire MinIO:
 *   1. Install the `minio` npm package and create a MinioClient with
 *      MINIO_ENDPOINT / MINIO_ACCESS_KEY / MINIO_SECRET_KEY from config.
 *   2. Replace the TODO block below with:
 *        const storageKey = `uploads/${Date.now()}-${req.file.originalname}`;
 *        await minioClient.putObject(config.minioBucket, storageKey, req.file.buffer);
 *        return res.json({ id: storageKey, name: req.file.originalname, storageKey });
 */
import { Router, Request, Response } from 'express';
import multer from 'multer';
import { requireAuth } from '../middleware/requireAuth';

const upload = multer({ storage: multer.memoryStorage(), limits: { fileSize: 50 * 1024 * 1024 } });

export const filesRouter = Router();
filesRouter.use(requireAuth);

filesRouter.post('/', upload.single('file'), (req: Request, res: Response) => {
  if (!req.file) {
    res.status(400).json({ error: 'No file uploaded' });
    return;
  }

  // TODO: upload req.file.buffer to MinIO and return a real storageKey
  const storageKey = `STUB/${Date.now()}-${req.file.originalname}`;
  console.warn('[files] MinIO upload STUBBED — file not persisted:', req.file.originalname);

  res.status(201).json({
    id: storageKey,
    name: req.file.originalname,
    storageKey,
    _stub: true,
  });
});
