#!/usr/bin/env python3

import json
import sys

"""
SubagentStart hook — injects the mandatory graphify-first rule into every
subagent's context so Explore and general-purpose agents follow the same
graphify-first, grep-last discipline as the main agent.
"""

print(json.dumps({
    'hookSpecificOutput': {
        'hookEventName': 'SubagentStart',
        'additionalContext': (
            'MANDATORY graphify-first rule (enforced by hook):\n'
            '1. Use graphify query "<concept>", graphify path <a> <b>, graphify explain <node> for ALL exploration.\n'
            '2. Start at graphify-out/GRAPH_REPORT.md to find the right community, then explore via graphify.\n'
            '3. Prefer graphify over grep/find/rg/ag/ripgrep/fd. A PreToolUse hook intercepts these: '
            'if graphify has results it blocks the grep and injects them; '
            'if graphify returns nothing it allows grep to proceed as a legitimate fallback.\n'
            '4. Only use Read on a specific file AFTER graphify has pointed you to it.\n'
            '5. If graphify truly cannot answer (e.g. the file is not indexed), '
            'state that explicitly and then use grep/find as fallback — it will be allowed.'
        ),
    },
}))
