#!/bin/bash

# Check that exactly 2 parameters were passed
if [ "$#" -ne 2 ]; then
    echo "Usage: $0 <filepath> <searchstring>"
    exit 1
fi

DATEIPFAD="$1"
SUCHSTRING="$2"
AUSGABEDATEI="extract_suchstring.log"

# Check that the source file exists
if [ ! -f "$DATEIPFAD" ]; then
    echo "Error: file '$DATEIPFAD' not found!"
    exit 1
fi

# Extraction via Python
python3 - "$DATEIPFAD" "$SUCHSTRING" "$AUSGABEDATEI" << 'EOF'
import sys

input_file, search_str, output_file = sys.argv[1], sys.argv[2], sys.argv[3]

with open(input_file, 'r', encoding='utf-8', errors='ignore') as infile, \
     open(output_file, 'w', encoding='utf-8') as outfile:

    lines = infile.readlines()

    for i, line in enumerate(lines):
        pos = line.find(search_str)
        if pos != -1:
            # 1. Text from the start of the line to the end of the search string
            start_to_match = line[:pos + len(search_str)]

            # 2. Collect the rest of the current line plus following lines (max 300 chars)
            remaining_text = line[pos + len(search_str):]

            j = i + 1
            while len(remaining_text) < 300 and j < len(lines):
                remaining_text += lines[j]
                j += 1

            # 3. Trim to exactly 300 characters after the search string
            after_match = remaining_text[:300]

            # Write the result to the file and prefix with a separator line
            outfile.write(start_to_match + after_match + "\n")
            outfile.write("-" * 50 + "\n")

EOF

echo "Extraction complete. Results were saved in '$AUSGABEDATEI'."