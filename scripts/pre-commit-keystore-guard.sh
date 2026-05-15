#!/usr/bin/env bash
# Install with: ln -sf "$(pwd)/scripts/pre-commit-keystore-guard.sh" .git/hooks/pre-commit
#
# Refuses commits that include release keystore material. `.gitignore` already
# covers these paths, but the patterns here protect against a stale local
# checkout where an earlier commit added the file before the ignore rule
# existed, or where someone forces `git add -f`.

set -euo pipefail

BLOCKED=$(git diff --cached --name-only --diff-filter=AM \
  | grep -E '(^|/)(keystore\.properties$|keystore_base64\.txt$|.*\.jks$|.*release\.jks$)' || true)

if [ -n "$BLOCKED" ]; then
  echo "ERROR: refusing to commit keystore material:" >&2
  echo "$BLOCKED" | sed 's/^/  /' >&2
  echo "Move these files outside the repo or unstage them and try again." >&2
  exit 1
fi
