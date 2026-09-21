/**
 * mpm のリポジトリインデックス（index.json）を生成する。
 *
 * `docs/mpm/plugins/*.json`（真実の源・コミット対象）を1枚のバンドルに束ね、
 * `docs/public/mpm/paper/index.json`（生成物・gitignore）として書き出す。
 *
 * 中央リポジトリ（repo.mpm.nikomaru.dev）の children.json がこのファイルのURLを指しており、
 * mpm（クライアント）が中央の index.json からリンクをたどってここを読む。
 * MineAuth 本体（core）と addon の定義はすべてここに置く。中央側には定義を持たない。
 *
 * addon を追加するときは docs/mpm/plugins/ に JSON を1つ足すだけでよい。
 * 中央リポジトリ側への変更は不要。
 *
 * 形式の仕様: https://mpm.plugin.morino.party/docs/repository/graph
 */

import { mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const docsDir = join(dirname(fileURLToPath(import.meta.url)), "..");
const sourceDir = join(docsDir, "mpm", "plugins");
const outputFile = join(docsDir, "public", "mpm", "paper", "index.json");

// 中央が children.json の allowedSources で許可している配布元（指定がある場合）。
// ここを外れた定義は配信してもクライアント側で捨てられるので、
// 気付かないまま公開しないよう手元でも検証する。
const ALLOWED_SOURCES = ["github:morinoparty/MineAuth", "modrinth:9LoU3yUC"];

// プラグイン名として mpm が受け付ける文字
const PLUGIN_NAME_PATTERN = /^[A-Za-z0-9_-]+$/;

const files = readdirSync(sourceDir)
    .filter((file) => file.endsWith(".json"))
    .sort();

const plugins = {};
const errors = [];

for (const file of files) {
    const name = file.replace(/\.json$/, "");
    const definition = JSON.parse(readFileSync(join(sourceDir, file), "utf-8"));

    // ファイル名と id の不一致はクライアント側で破棄されるため、ここで落とす
    if (definition.id !== name) {
        errors.push(`${file}: id (${definition.id}) がファイル名と一致しません`);
        continue;
    }
    if (!PLUGIN_NAME_PATTERN.test(name)) {
        errors.push(`${file}: プラグイン名に使えない文字が含まれています`);
        continue;
    }
    if (!Array.isArray(definition.repositories) || definition.repositories.length === 0) {
        errors.push(`${file}: repositories が空です`);
        continue;
    }

    // 配布元が中央の許可リストに収まっているかを検証する
    for (const repository of definition.repositories) {
        const actual = `${repository.type}:${repository.id}`.toLowerCase();
        const allowed = ALLOWED_SOURCES.some(
            (source) => source.toLowerCase() === actual,
        );
        if (!allowed) {
            errors.push(`${file}: 許可されていない配布元です (${actual})`);
        }
    }

    // クライアント側で落とされるフィールドは、ここでも出力しない。
    // $schema は手元のIDE補完用なので公開バンドルには含めない。
    const { $schema, ...rest } = definition;
    rest.repositories = rest.repositories.map((repository) => {
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
    $schema: "https://repo.mpm.nikomaru.dev/schema/repository-index/v1.json",
    schemaVersion: 1,
    name: "MineAuth",
    generated: new Date().toISOString(),
    plugins,
    // さらに下流のリポジトリへリンクする場合はここに { index: "https://.../index.json" } を足す
    children: [],
};

mkdirSync(dirname(outputFile), { recursive: true });
writeFileSync(outputFile, `${JSON.stringify(index, null, 2)}\n`);

console.log(
    `✓ Generated mpm index with ${Object.keys(plugins).length} plugins`,
);
