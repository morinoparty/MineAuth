"use client";
import { create } from "@orama/orama";
import { createTokenizer } from "@orama/tokenizers/japanese";
import { stopwords as japaneseStopwords } from "@orama/stopwords/japanese";
import { useDocsSearch } from "fumadocs-core/search/client";

const basePath = process.env.NEXT_PUBLIC_BASE_PATH || "";
import {
    SearchDialog,
    SearchDialogClose,
    SearchDialogContent,
    SearchDialogHeader,
    SearchDialogIcon,
    SearchDialogInput,
    SearchDialogList,
    SearchDialogOverlay,
    type SharedProps,
} from "fumadocs-ui/components/dialog/search";

// 日本語トークナイザーを使用したOramaデータベースを初期化
function initOrama() {
    return create({
        schema: { _: "string" },
        components: {
            tokenizer: createTokenizer({
                language: "japanese",
                stopWords: japaneseStopwords,
            }),
        },
    });
}

export default function DefaultSearchDialog(props: SharedProps) {
    const { search, setSearch, query } = useDocsSearch({
        type: "static",
        from: `${basePath}/api/search/index.json`,
        initOrama,
    });

    return (
        <SearchDialog search={search} onSearchChange={setSearch} isLoading={query.isLoading} {...props}>
            <SearchDialogOverlay />
            <SearchDialogContent>
                <SearchDialogHeader>
                    <SearchDialogIcon />
                    <SearchDialogInput />
                    <SearchDialogClose />
                </SearchDialogHeader>
                <SearchDialogList items={query.data !== "empty" ? query.data : null} />
            </SearchDialogContent>
        </SearchDialog>
    );
}
