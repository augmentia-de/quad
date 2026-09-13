#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 <folder>" >&2
  exit 1
fi

FOLDER="$1"
if [ ! -d "$FOLDER" ]; then
  echo "Error: '$FOLDER' is not a directory" >&2
  exit 1
fi

sudo chown -R "$(id -u):$(id -g)" "$FOLDER"
sudo chmod -R 777 "$FOLDER"
echo "Ownership and permissions updated for: $FOLDER"