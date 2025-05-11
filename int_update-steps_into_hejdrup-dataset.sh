#!/bin/bash


if [ -z "$1" ]; then
  echo "Usage: $0 <working-directory>"
  exit 1
fi

workdir="$1"

# Find and replace pattern in each matching file
find "$workdir" -type f -name "*_update-steps.csv" | while read -r file; do
  echo "Found $file"
  sed -E -i '' 's/_projectRun[0-9]+//g' "$file"
done



find "$workdir" -type f -name "*_update-steps.csv" | while read -r file; do

  echo "Found2 $file"
  # Strip path and extension
  basename="${file##*/}"
  basename="${basename%_update-steps.csv}"

  # Set IFS to underscore and read into an array
  IFS='_' read -ra parts <<< "$basename"

  new_path=$(IFS='/'; echo "${parts[*]}")
  echo "$new_path"
  cp $file "../projects/$new_path"

done