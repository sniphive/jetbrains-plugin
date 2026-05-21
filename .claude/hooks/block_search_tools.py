#!/usr/bin/env python3

import json
import re
import shlex
import subprocess
import sys

"""
PreToolUse hook — intercepts grep/find/rg/ripgrep/ag/fd from Bash tool calls.
If graphify has indexed results for the search term, the grep is blocked and
graphify results are injected instead. If graphify returns nothing, grep is
allowed to proceed as a legitimate fallback.
"""

data = json.load(sys.stdin)
cmd = data.get('tool_input', {}).get('command', '')

# Detect search tools used as standalone pipeline commands.
pattern = re.compile(
    r'(?:(?:^|\n|\[|;\]|&&|\|\|)\s*(?:sudo\s+)?)(grep|find|rg|ripgrep|ag|fd)(?:\s|$)',
    re.MULTILINE,
)

if not pattern.search(cmd):
    sys.exit(0)

def extract_term(command):
    """Return (term, tool) from a blocked search command."""
    try:
        parts = shlex.split(command)
    except ValueError:
        parts = command.split()

    tool_idx = None
    for i, p in enumerate(parts):
        if p.split('/')[-1] in ('grep', 'rg', 'ripgrep', 'ag', 'fd', 'find'):
            tool_idx = i
            break

    if tool_idx is None:
        return None, None

    tool = parts[tool_idx].split('/')[-1]
    args = parts[tool_idx + 1:]

    if tool == 'find':
        for i, arg in enumerate(args):
            if arg in ('-name', '-iname') and i + 1 < len(args):
                name = args[i + 1].replace('*', '').replace('?', '').lstrip('.')
                if name:
                    return name, tool
        return None, tool
    else:
        for arg in args:
            if not arg.startswith('-') and arg.strip():
                return arg.strip('\'"'), tool
        return None, tool

term, tool = extract_term(cmd)

graphify_bin = '/Users/mkilic/.local/bin/graphify'
graph_path = '/Users/mkilic/www/klc/sniphive/jetbrains-sniphive/graphify-out/graph.json'

# Auto-run graphify query and capture output
graphify_output = None
if term:
    try:
        result = subprocess.run(
            [graphify_bin, 'query', term, '--graph', graph_path],
            capture_output=True, text=True, timeout=15,
        )
        out = result.stdout.strip()
        if out and 'No matching nodes found' not in out:
            graphify_output = out
    except Exception:
        pass

if graphify_output:
    # Graphify found results — block grep and inject them
    print(json.dumps({
        'hookSpecificOutput': {
            'hookEventName': 'PreToolUse',
            'permissionDecision': 'deny',
            'permissionDecisionReason': f'BLOCKED — auto-ran: graphify query "{term}" (results injected into context)',
            'additionalContext': f'graphify query "{term}" results:\n{graphify_output}',
        },
    }))
    sys.exit(0)

# Graphify returned nothing — allow grep to proceed as legitimate fallback
if term:
    note = f'graphify query "{term}" returned no results — falling back to grep.'
else:
    note = 'graphify has no indexed results for this — falling back to grep.'

print(json.dumps({
    'hookSpecificOutput': {
        'hookEventName': 'PreToolUse',
        'additionalContext': note,
    },
}))
sys.exit(0)
