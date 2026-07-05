# 07 — Multi-turn & Memory (compaction)

**Why:** confirms the conversation keeps context across turns, and that long conversations still
remember early context after the rolling-summary compaction kicks in.

**Login as:** `rm_jane` / `Meridian@2024` at http://localhost:8099

---

## 7a. Context carries within a conversation
1. New chat → ask: `Give me a summary of the Whitman Family Office holdings`
2. Follow up (no name): `What is the largest position there?`

**Expected:** it answers about **Whitman** (uses the prior turn's context) without you re-naming the client.

## 7b. Long-conversation memory (the compaction test)
1. New chat. Turn 1: `Remember this: my focus this quarter is ESG-screened tech.`
2. Then ask 4–5 unrelated questions (e.g. "what's the parental leave policy", "what's the equities
   house view", "explain diversification to a cautious client", etc.) — enough to push turn 1 out of
   the immediate window.
3. Final turn: `What did I say my focus this quarter was?`

**Expected:** it recalls **"ESG-screened tech"** — carried by the rolling summary even after the early
turn scrolled out of the window.

---

## YOUR FEEDBACK
- **7a context carry:** answered about Whitman without re-naming? PASS / FAIL —
- **7b long-memory recall:** did it recall "ESG-screened tech" at the end? PASS / FAIL —
  - how many turns before the final recall question? ___
- **Did any turn lose the thread / answer the wrong thing?** YES / NO —
- **Overall:** PASS / FAIL / PARTIAL
