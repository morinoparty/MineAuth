import { existsSync, mkdirSync, renameSync, unlinkSync } from "fs";
import { dirname, join } from "path";

const outDir = "out";

// S3用にファイル構造を修正
// /api/search を /api/search/index.json に変換
const searchFile = join(outDir, "api", "search");
const searchDir = join(outDir, "api", "search");
const searchIndexFile = join(searchDir, "index.json");

if (existsSync(searchFile)) {
    // 一時的にリネーム
    const tempFile = join(outDir, "api", "search.tmp");
    renameSync(searchFile, tempFile);

    // ディレクトリ作成
    mkdirSync(searchDir, { recursive: true });

    // index.jsonとして配置
    renameSync(tempFile, searchIndexFile);

    console.log("✓ Converted /api/search to /api/search/index.json");
}

console.log("✓ Postbuild completed");
