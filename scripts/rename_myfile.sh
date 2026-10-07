#!/usr/bin/env bash

find . -type f -name 'MyFile*' -print0 |
while IFS= read -r -d '' f; do
    mv -- "$f" "$(dirname "$f")/YourFile${f##*MyFile}"
done
