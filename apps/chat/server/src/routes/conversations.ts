/**
 * Conversation CRUD + core streaming endpoint.
 *
 * POST /api/conversations/:id/messages — streaming endpoint:
 *   1. Persists the user message.
 *   2. Assembles context from the last CHAT_RECENT_MESSAGES messages
 *      (prepends a system summary if count > CHAT_SUMMARY_AFTER and a summary exists).
 *   3. Calls the Conduit gateway (OpenAI-compatible) with stream:true.
 *   4. Passes the SSE byte-stream through to the client AND accumulates
 *      the assistant content from the deltas.
 *   5. On stream end: persists the assistant Message, updates conversation
 *      updatedAt, and auto-titles the conversation on the first turn.
 *
 * Summary generation: STUBBED — generateSummary() returns '' and is a clear
 * seam for a follow-up LLM call.
 */
import { Router, Request, Response } from 'express';
import { Types } from 'mongoose';
import nodeFetch from 'node-fetch';
import { requireAuth } from '../middleware/requireAuth';
import { Conversation, Message } from '../db';
import { config } from '../config';

export const conversationsRouter = Router();
conversationsRouter.use(requireAuth);

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function isValidId(id: string): boolean {
  return Types.ObjectId.isValid(id);
}

/**
 * STUBBED — summary generation.
 * Replace with an LLM call that summarises the conversation so far and
 * stores the result in conversation.summary.
 */
async function generateSummary(_conversationId: string, _userId: string): Promise<string> {
  // TODO: call LLM to summarise, update Conversation.summary
  return '';
}

// ---------------------------------------------------------------------------
// Routes
// ---------------------------------------------------------------------------

/** GET /api/conversations */
conversationsRouter.get('/', async (req: Request, res: Response) => {
  const conversations = await Conversation.find({ userId: req.session.user!.id, archived: { $ne: true } })
    .sort({ updatedAt: -1 })
    .select('_id title projectId updatedAt')
    .lean();
  res.json(conversations);
});

/** POST /api/conversations */
conversationsRouter.post('/', async (req: Request, res: Response) => {
  const { title } = req.body as { title?: string };
  const conversation = await Conversation.create({
    userId: req.session.user!.id,
    title: title ?? 'New Conversation',
  });
  res.status(201).json(conversation);
});

/** GET /api/conversations/:id */
conversationsRouter.get('/:id', async (req: Request, res: Response) => {
  const id = req.params['id'] as string;
  if (!isValidId(id)) {
    res.status(404).json({ error: 'Not found' });
    return;
  }

  const conversation = await Conversation.findOne({
    _id: id,
    userId: req.session.user!.id,
  }).lean();

  if (!conversation) {
    res.status(404).json({ error: 'Not found' });
    return;
  }

  const messages = await Message.find({ conversationId: id })
    .sort({ createdAt: 1 })
    .lean();

  res.json({ conversation, messages });
});

/** PATCH /api/conversations/:id */
conversationsRouter.patch('/:id', async (req: Request, res: Response) => {
  const id = req.params['id'] as string;
  if (!isValidId(id)) {
    res.status(404).json({ error: 'Not found' });
    return;
  }

  const { title, projectId, archived } = req.body as {
    title?: string;
    projectId?: string;
    archived?: boolean;
  };

  const update: Record<string, unknown> = {};
  if (title !== undefined) update['title'] = title;
  if (projectId !== undefined) update['projectId'] = projectId;
  if (archived !== undefined) update['archived'] = archived;

  const conversation = await Conversation.findOneAndUpdate(
    { _id: id, userId: req.session.user!.id },
    update,
    { new: true },
  );

  if (!conversation) {
    res.status(404).json({ error: 'Not found' });
    return;
  }

  res.json(conversation);
});

/** DELETE /api/conversations/:id */
conversationsRouter.delete('/:id', async (req: Request, res: Response) => {
  const id = req.params['id'] as string;
  if (!isValidId(id)) {
    res.status(404).json({ error: 'Not found' });
    return;
  }

  const result = await Conversation.deleteOne({ _id: id, userId: req.session.user!.id });
  if (result.deletedCount === 0) {
    res.status(404).json({ error: 'Not found' });
    return;
  }

  // Cascade-delete messages
  await Message.deleteMany({ conversationId: id });

  res.status(204).send();
});

// ---------------------------------------------------------------------------
// POST /api/conversations/:id/messages — streaming
// ---------------------------------------------------------------------------
conversationsRouter.post('/:id/messages', async (req: Request, res: Response) => {
  const id = req.params['id'] as string;
  const userId = req.session.user!.id;
  const userToken = req.session.user!.token;

  if (!isValidId(id)) {
    res.status(404).json({ error: 'Not found' });
    return;
  }

  const { content } = req.body as { content?: string };
  if (!content || typeof content !== 'string' || content.trim() === '') {
    res.status(400).json({ error: 'content is required' });
    return;
  }

  // Verify ownership
  const conversation = await Conversation.findOne({ _id: id, userId }).lean();
  if (!conversation) {
    res.status(404).json({ error: 'Not found' });
    return;
  }

  // ── 1. Persist user message ──────────────────────────────────────────────
  await Message.create({
    conversationId: new Types.ObjectId(id),
    userId,
    role: 'user',
    content: content.trim(),
  });

  // ── 2. Assemble context ──────────────────────────────────────────────────
  const totalCount = await Message.countDocuments({ conversationId: id });

  const recentMessages = await Message.find({ conversationId: id })
    .sort({ createdAt: -1 })
    .limit(config.chatRecentMessages)
    .lean();
  recentMessages.reverse();

  const contextMessages: Array<{ role: string; content: string }> = [];

  // Prepend summary as a system message if the conversation is long enough
  if (totalCount > config.chatSummaryAfter && conversation.summary) {
    contextMessages.push({
      role: 'system',
      content: `Summary of earlier conversation:\n${conversation.summary}`,
    });
  } else if (totalCount > config.chatSummaryAfter && !conversation.summary) {
    // STUBBED: trigger summary generation asynchronously (fire-and-forget)
    generateSummary(id, userId).then((summary) => {
      if (summary) {
        Conversation.findByIdAndUpdate(id, { summary }).catch(console.error);
      }
    }).catch(console.error);
  }

  contextMessages.push(
    ...recentMessages.map((m) => ({ role: m.role, content: m.content })),
  );

  // ── 3. Call gateway with SSE stream ─────────────────────────────────────
  let gatewayRes: Awaited<ReturnType<typeof nodeFetch>>;
  try {
    gatewayRes = await nodeFetch(`${config.gatewayBase}/v1/chat/completions`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${userToken}`,
        'X-Conversation-Id': id,
      },
      body: JSON.stringify({
        model: 'conduit-assistant',
        stream: true,
        messages: contextMessages,
      }),
    });
  } catch (err) {
    console.error('[conversations/stream] gateway fetch failed:', err);
    res.status(502).json({ error: 'Gateway unreachable' });
    return;
  }

  if (!gatewayRes.ok) {
    const errText = await gatewayRes.text().catch(() => '');
    console.error('[conversations/stream] gateway error', gatewayRes.status, errText);
    res.status(502).json({ error: `Gateway returned ${gatewayRes.status}` });
    return;
  }

  // ── 4. Stream SSE through to client AND accumulate assistant content ──────
  res.setHeader('Content-Type', 'text/event-stream');
  res.setHeader('Cache-Control', 'no-cache');
  res.setHeader('Connection', 'keep-alive');
  res.setHeader('X-Accel-Buffering', 'no'); // disable nginx buffering if present
  res.flushHeaders();

  let assistantContent = '';
  let parseBuffer = '';

  const body = gatewayRes.body;
  if (!body) {
    res.end();
    return;
  }

  await new Promise<void>((resolve, reject) => {
    body.on('data', (chunk: Buffer) => {
      // Pass raw bytes through to the client
      res.write(chunk);

      // Accumulate for parsing
      parseBuffer += chunk.toString('utf8');

      // Process complete SSE lines
      let newlineIdx: number;
      while ((newlineIdx = parseBuffer.indexOf('\n')) !== -1) {
        const rawLine = parseBuffer.slice(0, newlineIdx);
        parseBuffer = parseBuffer.slice(newlineIdx + 1);
        const line = rawLine.trimEnd();

        if (!line.startsWith('data: ')) continue;
        const data = line.slice(6); // strip "data: "

        if (data === '[DONE]') continue;

        try {
          const parsed = JSON.parse(data) as {
            choices?: Array<{ delta?: { content?: string } }>;
          };
          const delta = parsed.choices?.[0]?.delta?.content;
          if (typeof delta === 'string') {
            assistantContent += delta;
          }
        } catch {
          // Malformed SSE chunk — ignore, keep streaming
        }
      }
    });

    body.on('error', reject);
    body.on('end', resolve);
  });

  // ── 5. Persist assistant message + update conversation ───────────────────
  try {
    await Message.create({
      conversationId: new Types.ObjectId(id),
      userId,
      role: 'assistant',
      content: assistantContent,
    });

    const conversationUpdate: Record<string, unknown> = { updatedAt: new Date() };

    // Auto-title: if the conversation has no title yet, use the first user message (truncated)
    if (!conversation.title || conversation.title === 'New Conversation') {
      conversationUpdate['title'] = content.trim().slice(0, 60);
    }

    await Conversation.findByIdAndUpdate(id, conversationUpdate);
  } catch (err) {
    // Don't fail the HTTP response (stream already sent); log and continue
    console.error('[conversations/stream] post-stream persist error:', err);
  }

  res.end();
});
