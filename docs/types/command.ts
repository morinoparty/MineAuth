/*
 * Written in 2023-2025 by Nikomaru <nikomaru@nikomaru.dev>
 *
 * To the extent possible under law, the author(s) have dedicated all copyright and related and neighboring rights to this software to the public domain worldwide.This software is distributed without any warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication along with this software.
 * If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
import { z } from "zod";

// コマンドのステータス
const commandStatusSchema = z.enum(["stable", "newly", "beta", "proposal", "deprecated"]);

// コマンドの引数
const commandArgumentSchema = z.object({
    name: z.string(), // 引数名 (例: "pluginName")
    required: z.boolean(), // 必須かどうか
});

// 個別のコマンド定義
const commandSchema = z.object({
    command: z.string(), // コマンド文字列 (例: "mpm add <pluginName>")
    status: commandStatusSchema, // コマンドのステータス
    category: z.string(), // カテゴリ (例: "manage", "repo")
    section: z.string().optional(), // セクション (例: "インストール・追加", "アンインストール・削除")
    description: z.string(), // コマンドの簡潔な説明
    arguments: z.array(commandArgumentSchema).optional(), // 引数の配列
});

// コマンド定義の配列
const commandsSchema = z.object({
    commands: z.array(commandSchema),
});

// 型をエクスポート
export type Command = z.infer<typeof commandSchema>;
export type Commands = z.infer<typeof commandsSchema>;

// スキーマをエクスポート
export { commandSchema, commandsSchema };
