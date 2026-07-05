# 05 — Conversation Management (sidebar)

**Why:** confirms the everyday chat plumbing — new/switch/rename/delete/archive/search — works and
conversations don't bleed into each other.

**Login as:** `rm_jane` / `Meridian@2024` at http://localhost:8099. Send a couple of messages first
so you have 2–3 conversations to work with.

---

| # | Do | Expected |
|---|---|---|
| 5a | Click **New Chat**, send a message | new conversation appears at the top of the sidebar |
| 5b | Switch between two conversations | each shows **its own** messages — no bleed between them |
| 5c | Rename a conversation (hover → pencil / edit) | title updates and sticks |
| 5d | Delete a conversation (hover → trash) | it's removed; if it was open, you land somewhere sane (no error) |
| 5e | Archive a conversation | it disappears from the active list |
| 5f | Search / filter the list | filters by title as you type |

---

## YOUR FEEDBACK
- 5a new chat: PASS / FAIL —
- 5b switch (no bleed): PASS / FAIL —
- 5c rename: PASS / FAIL —
- 5d delete (no error): PASS / FAIL —
- 5e archive: PASS / FAIL —
- 5f search: PASS / FAIL —
- **Anything glitchy (wrong messages, error toast, stuck):**
- **Overall:** PASS / FAIL / PARTIAL
