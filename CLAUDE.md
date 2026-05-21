# Before Every Task

1. **Orient with graphify** — start at `graphify-out/GRAPH_REPORT.md` (71 communities, 1325 nodes), then use `graphify query "<concept>"`, `graphify path <a> <b>`, or `graphify explain <node>` for exploration.
2. Check relevant source files identified by graphify before writing any code.
3. Think in **communities and relations**, not file paths — the graph surfaces connections grep cannot.

# Exploration: graphify-first, grep-last (MANDATORY)

For any "where is X / what uses Y / how does Z connect" question, **you must use graphify before any grep, find, ripgrep, or Glob/Grep tool call.** Graphify is ~71x cheaper in tokens and surfaces relationships (node → prompt → output model → state field) that grep cannot.

**Required workflow:**

1. Start at `graphify-out/GRAPH_REPORT.md` to find the right community, or run `graphify query "<concept>"`.
2. Follow relations with `graphify path <a> <b>` and `graphify explain <node>`.
3. Think in **communities and relations**, not file paths.

**Grep/find is allowed only as a fallback for:**
- Exact literal hunts (error strings, magic constants, specific config keys).
- Files graphify hasn't indexed (state it explicitly when this happens).
- Verifying a specific line/symbol after graphify has pointed you to the file.

**Subagents must follow the same rule** — when delegating exploration (Explore, general-purpose, etc.), instruct them to use graphify first and treat grep as fallback. Do not let the default Explore agent reflexively grep this repo.

If graphify output looks stale, surface that to the user instead of silently switching to grep.
