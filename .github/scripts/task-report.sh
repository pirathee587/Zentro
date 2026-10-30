#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 [--since 7d]" >&2
}

since="7d"
if [[ $# -gt 0 ]]; then
  if [[ $# -ne 2 || "$1" != "--since" || ! "$2" =~ ^[0-9]+d$ ]]; then
    usage
    exit 2
  fi
  since="$2"
fi

command -v gh >/dev/null 2>&1 || { echo "Error: GitHub CLI (gh) is required." >&2; exit 1; }
gh auth status >/dev/null
repo="$(gh repo view --json nameWithOwner --jq .nameWithOwner)"

echo "Task report for $repo"
echo
echo "## Open issues per assignee"
gh issue list --repo "$repo" --state open --limit 1000 \
  --json number,title,assignees,url \
  --jq 'sort_by((.assignees[0].login // "unassigned")) | group_by((.[0].assignees[0].login // "unassigned"))[] | ((.[0].assignees[0].login // "unassigned") + ":") as $owner | $owner, (.[] | "  #\(.number) \(.title) — \(.url)")'

echo
echo "## Open pull requests"
gh pr list --repo "$repo" --state open --limit 1000 \
  --json number,title,headRefName,baseRefName,url \
  --jq '.[] | "#\(.number) \(.headRefName) -> \(.baseRefName): \(.title) — \(.url)"'

echo
echo "## Pull requests merged in the last $since"
days="${since%d}"
cutoff="$(date -u -d "$days days ago" +%Y-%m-%dT%H:%M:%SZ)"
gh pr list --repo "$repo" --state merged --limit 1000 --search "merged:>=$cutoff" \
  --json number,title,mergedAt,url \
  --jq '.[] | "#\(.number) \(.title) — merged \(.mergedAt) — \(.url)"'

echo
echo "## Issues closed by merged pull requests"
gh api --paginate "repos/$repo/issues?state=closed&per_page=100" \
  --jq '.[] | select(has("pull_request") | not) | . as $issue | ($issue.closed_by_pull_requests_references // [])[]? | "#\($issue.number) \($issue.title) — closed by PR #\(.number) ((.url))"'
