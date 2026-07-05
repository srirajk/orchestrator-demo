# 02 — Authorization: allow vs deny across personas

**Why:** confirms the 3-gate ABAC (segment → classification → coverage) surfaces correctly in the UI —
the right people get grounded answers, the wrong ones get denied for the right reason.

Log in/out as each persona below (all password `Meridian@2024`). Use the **exact** prompts.

---

| # | Login as | Ask | Expected |
|---|---|---|---|
| 2a | `rm_jane` | `Give me a summary of the Whitman Family Office holdings` | ✅ grounded answer (~$1.97M; JPM/MSFT/AAPL) |
| 2b | `rm_jane` | `Show me Okafor holdings` | ❌ denied — not her client (coverage) |
| 2c | `rm_jane` | `Show me the details of insurance policy POL-77001` | ❌ denied — not in insurance (segment) |
| 2d | `rm_jane` | `What is the parental leave policy?` | ✅ HR answer (open to everyone) |
| 2e | `uw_sam` | `Show me the details of insurance policy POL-77001` | ✅ grounded (Continental Freight, premium 48,500) |
| 2f | `uw_sam` | `Give me a summary of the Whitman Family Office holdings` | ❌ denied — not in wealth (segment) |
| 2g | `analyst_amy` | `What is the equities house view?` | ✅ market-research answer |
| 2h | `analyst_amy` | `Give me a summary of the Whitman Family Office holdings` | ❌ denied — not PII-cleared (classification) |
| 2i | `rm_guest` | `Give me a summary of the Whitman Family Office holdings` | ❌ denied — empty book (coverage); no value leaks |

Tip: to switch personas, log out (bottom-left) then log in as the next one.

---

## YOUR FEEDBACK
Mark each PASS/FAIL; note anything that answered when it should've denied (or vice-versa):

- 2a rm_jane Whitman: PASS / FAIL —
- 2b rm_jane Okafor (deny): PASS / FAIL —
- 2c rm_jane insurance (deny): PASS / FAIL —
- 2d rm_jane HR (allow): PASS / FAIL —
- 2e uw_sam policy (allow): PASS / FAIL —
- 2f uw_sam wealth (deny): PASS / FAIL —
- 2g analyst_amy research (allow): PASS / FAIL —
- 2h analyst_amy holdings (deny): PASS / FAIL —
- 2i rm_guest Whitman (deny, no leak): PASS / FAIL —
- **Overall:** PASS / FAIL / PARTIAL
