# Conduit Chat — Manual Test Pack

Fresh reset baseline (one docker project `orchestrator-demo`, all services healthy, verified).
Work through the files in order; **01 is the most important.** After each test, fill in the
**YOUR FEEDBACK** section right in that file, save it, and tell me which files you updated.

---

## The app
- **URL:** http://localhost:8099
- **Login:** you'll be redirected to the Axiom sign-in page, enter the username + password, then you're in.

## Logins — all password: `Meridian@2024`
| Username | Access (from their token) | Use them to test |
|---|---|---|
| `rm_jane` | wealth **PII** + servicing | Whitman ✅ · Okafor ❌(coverage) · insurance ❌(segment) · HR ✅ |
| `rm_carlos` | wealth **PII** only | Sterling ✅ · **partial-access notice** (servicing withheld) |
| `uw_sam` | insurance **PII** only | policy POL-77001 ✅ · wealth ❌(segment) |
| `analyst_amy` | wealth **confidential** (NOT pii) | market research ✅ · holdings ❌(classification) |
| `rm_guest` | wealth PII, **empty book** | every client ❌(coverage) — nothing leaks |

## Optional (not part of the core chat tests)
- Axiom admin console: http://localhost:5182
- Grafana dashboards: http://localhost:3000  (`admin` / `changeme`)

---

## The files
| File | Scenario | Priority |
|---|---|---|
| 01 | Message rendering & persistence (the residual render race) | ⭐ do first, repeat |
| 02 | Authorization — allow vs deny across personas | high |
| 03 | Access notices — red (denied) vs blue (partial) | high |
| 04 | Glass-box decision trace (right panel) | medium |
| 05 | Conversation management (sidebar CRUD) | medium |
| 06 | Session & logout | high |
| 07 | Multi-turn & memory (compaction) | medium |

## How to give feedback
In each file, edit the **YOUR FEEDBACK** block — mark PASS / FAIL / PARTIAL and jot what you saw.
Screenshots optional (paste a path). When done with a file, just tell me "01 and 03 are updated"
and I'll read them and act.
