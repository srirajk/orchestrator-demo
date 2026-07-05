# 01 — Message Rendering & Persistence  ⭐ MOST IMPORTANT

**Why:** confirms the core render fix, and catches the residual timing race (finding H1) where the
assistant answer can *occasionally* vanish right after it finishes streaming. This is the exact bug
you reported, so this file is the one that matters most.

**Login as:** `rm_jane` / `Meridian@2024` at http://localhost:8099

---

## 1a. Fresh render — DO THIS 3–4 TIMES (the race is intermittent)
1. Click **New Chat**.
2. Ask: `Give me a summary of the Whitman Family Office holdings`
3. Watch the center pane as the answer streams and finishes.

**Expected:** your question bubble + the assistant answer both stay visible after streaming ends —
every time. The answer must NOT disappear once it completes.
⚠️ Send it **3–4 separate times** (new chat each time). A single pass can look fine and hide the race.

## 1b. Refresh persistence
1. After 1a, press **F5** to reload the page.

**Expected:** the conversation and its messages still render (not the empty "Start a conversation").

## 1c. Multi-turn in the same chat
1. In the same conversation, ask: `What is the largest position?`

**Expected:** the new question + answer render **together with** the earlier turn (nothing drops).

---

## YOUR FEEDBACK
- **1a fresh render — how many of your 4 sends rendered fully?**  ___ / 4
- **Did the answer ever vanish after it finished streaming?**  YES / NO
  - if YES: did it come back on refresh?  YES / NO
- **1b refresh persistence:**  PASS / FAIL
- **1c multi-turn:**  PASS / FAIL
- **Anything weird (flicker, duplicate bubble, stuck "…"):**
- **Screenshot path (optional):**
- **Overall for this file:**  PASS / FAIL / PARTIAL
