#!/usr/bin/env bash
# Fetches the pinned llama.cpp release into ./llama.cpp (used by the CMake build).
set -euo pipefail
TAG="${1:-b6800}"
cd "$(dirname "$0")/.."
rm -rf llama.cpp
git clone --depth 1 --branch "$TAG" https://github.com/ggml-org/llama.cpp llama.cpp
echo "llama.cpp $TAG ready"
