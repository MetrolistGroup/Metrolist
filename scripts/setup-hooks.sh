#!/bin/bash
set -eu
export LC_ALL=C

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

git config core.hooksPath .githooks
