#!/usr/bin/env sh
# Starts the JARVIS PC Agent on Linux or macOS.
python3 -m pip install --quiet cryptography
exec python3 "$(dirname "$0")/jarvis_pc_agent.py" "$@"
