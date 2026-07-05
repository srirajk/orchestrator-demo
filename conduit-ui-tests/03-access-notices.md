# 03 — Access Notices: red (denied) vs blue (partial)

**Why:** confirms Codex's notice fix — a **full denial** looks like a red alert, but a query that
still returns an answer while *some* data was withheld shows a **soft blue "Partial access"** notice
(not a scary red one). This was the B9 bug (a correct answer wrongly showing "Access denied").

Login as noted (all `Meridian@2024`) at http://localhost:8099.

---

## 3a. Partial access — should be BLUE, informational
1. Login as `rm_carlos`.
2. Ask: `Give me a summary of Sterling Capital Partners holdings`

**Expected:** you GET the Sterling answer, **and** a **soft blue** "Partial access" notice saying you
don't have access to the asset-servicing data for this client. **NOT** a red "Access denied" banner.

## 3b. Full denial — should be RED
1. Login as `rm_jane`.
2. Ask: `Show me Okafor holdings`

**Expected:** a **red "Access denied"** notice, plain language ("you don't have access to this client
relationship"), and **no** answer.

## 3c. Clean success — should be NO banner
1. As `rm_jane`, ask: `Give me a summary of the Whitman Family Office holdings`

**Expected:** just the answer — **no** notice of any kind.

---

## YOUR FEEDBACK
- **3a rm_carlos Sterling:** answer shown? YES/NO · notice colour: BLUE / RED / NONE · wording OK (plain, names servicing)? 
- **3b rm_jane Okafor:** notice colour: RED / BLUE / NONE · wording OK? 
- **3c rm_jane Whitman:** any banner shown? YES (bad) / NO (good)
- **Did any correct answer come with a scary red banner?** YES / NO —
- **Screenshot path (optional):**
- **Overall:** PASS / FAIL / PARTIAL
