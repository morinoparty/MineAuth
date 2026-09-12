/**
 * mpm の委譲リポジトリ用インデックスを生成する。
 *
 * `docs/mpm/plugins/*.json`（真実の源・コミット対象）を1枚のバンドルに束ね、
 * `docs/public/mpm/paper/index.json`（生成物・gitignore）として書き出す。
 *
 * 中央リポジトリ（repo.mpm.nikomaru.dev）の MineAuth.json が
 * `delegate.index` でこのファイルのURLを指しており、
 * `MineAuth-` で始まる名前の解決がここへ委譲される。
 *
 * addon を追加するときは docs/mpm/plugins/ に JSON を1つ足すだけでよい。
 * 中央リポジトリ側への変更は不要。
 */

import { mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const docsDir = join(dirname(fileURLToPath(import.meta.url)), "..");
const sourceDir = join(docsDir, "mpm", "plugins");
const outputFile = join(docsDir, "public", "mpm", "paper", "index.json");

// 委譲元のプラグインid。中央の MineAuth.json の id と一致していなければならない。
// 委譲スコープはここから導出されるため、`MineAuth-` で始まらない名前は中央側で破棄される。
const ROOT = "MineAuth";

// 中央が委譲先に許可している配布元。中央の delegate.allowedSources と揃える。
// ここを外れた定義は配信しても中央のサニタイズで捨てられるので、
// 気付かないまま公開しないよう手元でも検証する。
const ALLOWED_SOURCES = ["github:morinoparty/MineAuth", "modrinth:9LoU3yUC"];

const files = readdirSync(sourceDir).filter((file) => file.endsWith(".json"));

const plugins = {};
const errors = [];

for (const file of files) {
    const name = file.replace(/\.json$/, "");
    const definition = JSON.parse(readFileSync(join(sourceDir, file), "utf-8"));

    // ファイル名と id の不一致は中央側で破棄されるため、ここで落とす
    if (definition.id !== name) {
        errors.push(`${file}: id (${definition.id}) がファイル名と一致しません`);
        continue;
    }

    // 委譲スコープ外の名前は中央に配信しても無視される
    if (!name.startsWith(`${ROOT}-`)) {
        errors.push(`${file}: 委譲スコープ (${ROOT}-*) の外です`);
        continue;
    }

    // 配布元が中央の許可リストに収まっているかを検証する
    for (const repository of definition.repositories ?? []) {
        const actual = `${repository.type}:${repository.id}`.toLowerCase();
        const allowed = ALLOWED_SOURCES.some(
            (source) => source.toLowerCase() === actual,
        );
        if (!allowed) {
            errors.push(`${file}: 許可されていない配布元です (${actual})`);
        }
    }

    // 中央のサニタイズで落とされるフィールドは、ここでも出力しない。
    // $schema は手元のIDE補完用なので公開バンドルには含めない。
    const { $schema, delegate, ...rest } = definition;
    rest.repositories = (rest.repositories ?? []).map((repository) => {
        const { downloadUrl, fileNameTemplate, ...repositoryRest } = repository;
        return repositoryRest;
    });

    plugins[name] = rest;
}

if (errors.length > 0) {
    for (const error of errors) {
        console.error(`✗ ${error}`);
    }
    process.exit(1);
}

const index = {
    $schema: "https://repo.mpm.nikomaru.dev/schema/delegation-index/v1.json",
    schemaVersion: 1,
    root: ROOT,
    plugins,
};

mkdirSync(dirname(outputFile), { recursive: true });
writeFileSync(outputFile, `${JSON.stringify(index, null, 2)}\n`);

console.log(
    `✓ Generated mpm index with ${Object.keys(plugins).length} plugins`,
);
