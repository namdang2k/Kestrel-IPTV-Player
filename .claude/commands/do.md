---
description: Plan a feature (or update its existing plan), then execute it yourself when told to
---

Follow the workflow in CLAUDE.md for: $ARGUMENTS

No agents, no subagents, no MCP servers — every step happens in this session with the file and shell tools.

1. **Find the feature's plan.** `ls plans | grep -i <topic>` and `grep -ril <symbol> plans/`. One plan per
   feature: if one exists, this request updates it — do not start a new plan.

2. **Investigate** read-only until you can name every file and symbol that must change. Open an API's
   source rather than guessing it.

3. **Write or update the plan** at `plans/NNN-slug.md` (new number only for a new feature:
   `ls plans | grep -o '^[0-9]*' | sort -n | tail -1`). Sections: Goal / Architecture rules / Files
   (CREATE/MODIFY) / Steps / Verify / Do NOT / Revisions / Outcome notes. When updating, rewrite the
   sections so the plan describes the feature as it should now be, and add a dated line under
   `## Revisions`.

4. **Stop and summarise the plan.** Execute only when the user says so, or if this request explicitly
   asked for the work to be done.

5. **Execute it yourself** exactly as written, touching only the files under `## Files`.

6. **Verify** per CLAUDE.md: `git status --short` for scope, the full diff against the rules, encoding, and a
   grep cross-check of every symbol, field and GUID the change relies on.

7. **Report and record**: what changed, what you verified and how, and the Unity checks the user still has
   to run (compile + Console, validators, play mode). Then update the plan's `## Outcome notes`. Never
   report unverified work as done.
