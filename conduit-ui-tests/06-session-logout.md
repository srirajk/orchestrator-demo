# 06 — Session & Logout

**Why:** confirms the auth-robustness fixes — logout actually logs you out (Codex's OIDC single-logout
fix), and an expired session sends you back to login instead of hanging.

**Login as:** `rm_jane` / `Meridian@2024` at http://localhost:8099

---

## 6a. Logout works cleanly
1. Click the **logout** icon (bottom-left, the exit/door icon).

**Expected:** you land on the **Axiom login page**, cleanly — **no** "Internal error" / 500, no blank
screen.

## 6b. Logout is real (not just local)
1. After 6a, go back to http://localhost:8099.

**Expected:** you're asked to **log in again** — it does NOT silently walk you straight back into the
app already logged in. (This was the bug: logout cleared the app but Axiom kept you signed in.)

## 6c. Log back in
1. Log in again as `rm_jane`.

**Expected:** clean login, back into a working chat.

## 6d. (optional, harder) Session survives a normal refresh
1. While logged in, press F5 a few times.

**Expected:** you stay logged in (no surprise bounce to login on a plain refresh).

---

## YOUR FEEDBACK
- **6a logout — clean landing on login, no error?** PASS / FAIL —
- **6b logout is real — asked to log in again?** PASS / FAIL —  (if it walked you straight back in = FAIL)
- **6c log back in:** PASS / FAIL —
- **6d refresh keeps session:** PASS / FAIL —
- **Any error message you saw (copy it):**
- **Overall:** PASS / FAIL / PARTIAL
