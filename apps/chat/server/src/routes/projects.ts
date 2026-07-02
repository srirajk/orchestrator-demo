import { Router, Request, Response } from 'express';
import { Types } from 'mongoose';
import { requireAuth } from '../middleware/requireAuth';
import { Project } from '../db';

export const projectsRouter = Router();
projectsRouter.use(requireAuth);

/** GET /api/projects — list user's projects */
projectsRouter.get('/', async (req: Request, res: Response) => {
  const projects = await Project.find({ userId: req.session.user!.id })
    .sort({ createdAt: -1 })
    .lean();
  res.json(projects);
});

/** POST /api/projects — create a project */
projectsRouter.post('/', async (req: Request, res: Response) => {
  const { name, color } = req.body as { name?: string; color?: string };
  if (!name) {
    res.status(400).json({ error: 'name is required' });
    return;
  }
  const project = await Project.create({
    userId: req.session.user!.id,
    name,
    color,
  });
  res.status(201).json(project);
});

/** GET /api/projects/:id */
projectsRouter.get('/:id', async (req: Request, res: Response) => {
  if (!Types.ObjectId.isValid(req.params['id'] as string)) {
    res.status(404).json({ error: 'Not found' });
    return;
  }
  const project = await Project.findOne({
    _id: req.params['id'],
    userId: req.session.user!.id,
  }).lean();
  if (!project) {
    res.status(404).json({ error: 'Not found' });
    return;
  }
  res.json(project);
});

/** PATCH /api/projects/:id */
projectsRouter.patch('/:id', async (req: Request, res: Response) => {
  if (!Types.ObjectId.isValid(req.params['id'] as string)) {
    res.status(404).json({ error: 'Not found' });
    return;
  }
  const { name, color } = req.body as { name?: string; color?: string };
  const project = await Project.findOneAndUpdate(
    { _id: req.params['id'], userId: req.session.user!.id },
    { ...(name !== undefined && { name }), ...(color !== undefined && { color }) },
    { new: true },
  );
  if (!project) {
    res.status(404).json({ error: 'Not found' });
    return;
  }
  res.json(project);
});

/** DELETE /api/projects/:id */
projectsRouter.delete('/:id', async (req: Request, res: Response) => {
  if (!Types.ObjectId.isValid(req.params['id'] as string)) {
    res.status(404).json({ error: 'Not found' });
    return;
  }
  const result = await Project.deleteOne({
    _id: req.params['id'],
    userId: req.session.user!.id,
  });
  if (result.deletedCount === 0) {
    res.status(404).json({ error: 'Not found' });
    return;
  }
  res.status(204).send();
});
