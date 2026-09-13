import re

input_file = "logs/quad.log"  # Pfad zu deiner Quell-Logdatei
output_file = "llm.log"
search_pattern = r"\[de\.au\.no\.co\.ob\.LoggingHook\]"

with open(input_file, "r", encoding="utf-8", errors="ignore") as f:
    content = f.read()

# Sucht alle Vorkommnisse und greift das Muster + die nächsten 400 Zeichen ab
matches = re.finditer(rf"{search_pattern}.{{0,1000}}", content, re.DOTALL)

with open(output_file, "w", encoding="utf-8") as f:
    for i, match in enumerate(matches, 1):
        f.write(f"--- TREFFER {i} ---\n")
        f.write(match.group(0))
        f.write("\n\n" + "=" * 40 + "\n\n")

print("Extraktion abgeschlossen.")
