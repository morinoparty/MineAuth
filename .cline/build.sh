#!/usr/bin/env bash
# プロンプトファイルを結合して複数の .clinerules ファイルを生成するスクリプト

RULES_DIR=".cline/rules"
OUTPUT_FILES="AGENTS.md CLAUDE.md"

END="それでは、指示に従ってタスクを遂行してください。

<指示>
{{instructions}}"

# 出力ファイルを初期化
for output_file in $OUTPUT_FILES; do
  echo "" > $output_file
done

# ルールファイルを結合
for file in "$RULES_DIR"/*.md; do
  if [[ -f "$file" ]]; then
    for output_file in $OUTPUT_FILES; do
      cat "$file" >> $output_file
      echo -e "\n\n" >> $output_file  # 各ファイルの間に改行を追加
    done
  fi
done

for output_file in $OUTPUT_FILES; do
  echo "$END" >> $output_file
done

echo "Generated $(echo $OUTPUT_FILES | tr ' ' ', ') from $(ls -1 "$RULES_DIR"/*.md | wc -l) prompt files"