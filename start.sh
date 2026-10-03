#!/usr/bin/env bash
# Short alias; the existing start-web launcher remains the single implementation.
set -e
exec bash "$(dirname -- "${BASH_SOURCE[0]}")/start-web.sh" "$@"
