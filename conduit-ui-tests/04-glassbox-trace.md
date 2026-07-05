# 04 — Glass-box Decision Trace (right panel)

**Why:** the whole pitch is a "glass box" — every answer should show its live decision trail on the
right (intent → agents selected → authorization gates → answer ready). Confirms the trace panel
populates and tells the truth for both allows and denies.

**Login as:** `rm_jane` / `Meridian@2024` at http://localhost:8099

---

## 4a. Trace on an ALLOWED query
1. Ask: `Give me a summary of the Whitman Family Office holdings`
2. Look at the right-hand **Decision trace** panel.

**Expected:** it fills in live — intent classified, agents resolved, **gates passing**, ending at
something like **"Answer Ready"**. It should track the answer, not sit empty.

## 4b. Trace on a DENIED query
1. New chat → ask: `Show me Okafor holdings`
2. Watch the trace panel.

**Expected:** the trace shows the **coverage gate denying** (you can see *where* it stopped), matching
the red denial notice in the center.

## 4c. Collapse / expand
1. Toggle the trace rail collapsed and back.

**Expected:** collapses/expands cleanly; the center chat stays usable either way.

---

## YOUR FEEDBACK
- **4a allowed-query trace:** populated & reached "Answer Ready"? PASS / FAIL —
- **4b denied-query trace:** showed the gate/deny clearly? PASS / FAIL —
- **4c collapse/expand:** PASS / FAIL —
- **Was the trace readable / did it make sense?** YES / NO —
- **Screenshot path (optional):**
- **Overall:** PASS / FAIL / PARTIAL
