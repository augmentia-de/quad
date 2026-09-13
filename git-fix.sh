#!/bin/bash

# 1. Load all deleted files (tracked but deleted) into an array
deleted_files=($(git status --porcelain | grep '^.D ' | awk '{print $2}'))

# 2. Load all new/unversioned files into an array
new_files=($(git status --porcelain | grep '^?? ' | awk '{print $2}'))

if [ ${#deleted_files[@]} -eq 0 ] || [ ${#new_files[@]} -eq 0 ]; then
    echo "No matching deleted or new files found."
    exit 0
fi

echo "Searching for matching pairs for the move..."
echo "------------------------------------------------"

# 3. Loop through the deleted files
for del in "${deleted_files[@]}"; do
    del_base=$(basename "$del")

    # Search for a new file with the same base name (e.g. MyClass.java)
    for new in "${new_files[@]}"; do
        new_base=$(basename "$new")

        if [ "$del_base" == "$new_base" ]; then
            echo "Match found for class: $del_base"
            echo "  Old: $del"
            echo "  New: $new"

            # Tell git that the old one was deleted and the new one was added
            git rm "$del" --quiet
            git add "$new"

            echo "  -> Marked as 'moved' in the git index!"
            echo "------------------------------------------------"
            break
        fi
    done
done

echo "Done! Check the result with 'git status'."