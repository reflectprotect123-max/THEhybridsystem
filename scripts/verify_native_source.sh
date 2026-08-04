#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

forbidden_pattern='\b(react[ -]native|expo|flutter|webview|javascript[[:space:]]+bridge)\b'
if rg -n -i "$forbidden_pattern" app/src/main; then
  echo "Native source check failed: a non-native client reference is present."
  exit 1
fi

if rg -n 'androidx\.compose\.ui\.platform\.LocalLifecycleOwner' app/src/main; then
  echo "Native source check failed: LocalLifecycleOwner has the wrong import."
  exit 1
fi

if rg -n 'TODO\(' app/src/main app/src/test; then
  echo "Native source check failed: placeholder TODO implementation remains."
  exit 1
fi

python3 -m unittest discover -s tests -v
python3 -m py_compile *.py

echo "Native source audit passed."
