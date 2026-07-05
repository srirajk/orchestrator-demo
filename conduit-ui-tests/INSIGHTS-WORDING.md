# Conduit Insights — Wording & Terminology (CANONICAL — use everywhere)

This is the single source of truth for how the Insights product is named and worded. Apply it to
**every** string — nav, headings, buttons, empty states, errors, the access-denied page, board titles.

## The name
- **Conduit Insights** (full) · **Insights** (short). That is the product.
- **NEVER call it:** "console", "workspace", "analytics workspace", "dashboard app", "reporting tool",
  "portal". It is **Insights**.
- Descriptor/subtitle (OK — as *category*, never the name): "Axiom-secured operational analytics" or
  "operational analytics for Conduit". "Analytics" describes what it does; the *name* stays **Insights**.

## Who it's for
- The gated role: **Conduit Insights administrators** (short: "Insights administrators" / "an Insights admin").

## Access-denied page — corrected copy (replace the current wording)
- **Heading:** `You don't have access to Insights`  *(softer than "ACCESS NOT GRANTED", which reads like an error)*
- **Body:** `You're signed in as {name}, but Conduit Insights is available only to administrators. Contact your Conduit admin if you need access.`
- **Remove** the word "workspace." Keep the "signed in successfully, but…" reassurance (auth worked; they just lack the entitlement) and add the path forward ("contact your admin").

## The 7 boards — canonical titles (use these exact labels in the nav + headers)
1. **Executive Overview**
2. **Governance & Trust**
3. **AI Pipeline**
4. **Agent Fleet**
5. **Gateway Deep-Dive**
6. **Live Decision Trace**
7. **Quality & Economics**
> The `/v1/insights/boards/{id}` API groups the data; in the UI, **label each board with the matching
> canonical title above based on its content** (not the API's internal shorthand). If a board's content
> doesn't cleanly map to one of these, flag it rather than inventing a new name.

## Voice
- Enterprise-calm, precise, confident. Not chatty, not salesy in-product.
- Refer to features by what they are: "the decision trace", "the agent fleet", "entitlement decisions".
- Numbers speak for themselves — labels stay short and factual.

## Quick do / don't
| Don't | Do |
|---|---|
| "the Insights workspace" | "Conduit Insights" / "Insights" |
| "the analytics console" | "Conduit Insights" |
| "ACCESS NOT GRANTED" | "You don't have access to Insights" |
| "limited to Conduit Insights administrators" | "available only to administrators" |
