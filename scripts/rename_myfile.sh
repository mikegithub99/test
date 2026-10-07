#!/usr/bin/env bash

find . -type f -name '*MyFile*' -exec sh -c '
  for f; do
    dir=${f%/*}
    name=${f##*/}
    newname=${name//MyFile/YourFile}
    mv -- "$f" "$dir/$newname"
  done
' sh {} +
