import { createPreset, stone } from "@morinoparty/chlorophyll-react/preset";
import { defineConfig } from "@pandacss/dev";

// この画面で使う Chlorophyll のレシピ。Chlorophyll のレシピはすべて staticCss: ["*"] なので、
// 使わないものを取り除かないと全コンポーネントの全バリアントの CSS が HTML に埋め込まれてしまう
const USED_RECIPES = ["button", "separator"];
const USED_SLOT_RECIPES: string[] = [];

export default defineConfig({
  preflight: true,
  // 生成物のクラス名・CSS 変数を Chlorophyll 自身（mpc）と衝突させない
  prefix: "ma",
  presets: ["@pandacss/preset-base", createPreset({ brandColor: "mori", grayColor: stone, radius: "md" })],
  include: [
    "./src/**/*.{ts,tsx}",
    // Chlorophyll のコンポーネントは css() / recipe をソースのまま持つので、使うものだけ静的解析の対象にする
    "./node_modules/@morinoparty/chlorophyll-react/src/components/{separator,styled}/**/*.tsx",
    "./node_modules/@morinoparty/chlorophyll-react/src/components/button.tsx",
  ],
  exclude: [],
  jsxFramework: "react",
  outdir: "styled-system",
  hooks: {
    // プリセットを合成した後の設定から、使わないレシピと umi パレットのトークンを取り除く
    "config:resolved": ({ config, utils }) => {
      const unused = [
        "theme.tokens.colors.umi",
        "theme.semanticTokens.colors.umi",
        ...Object.keys(config.theme?.recipes ?? {})
          .filter((name) => !USED_RECIPES.includes(name))
          .map((name) => `theme.recipes.${name}`),
        ...Object.keys(config.theme?.slotRecipes ?? {})
          .filter((name) => !USED_SLOT_RECIPES.includes(name))
          .map((name) => `theme.slotRecipes.${name}`),
      ];
      return utils.omit(config, unused) as typeof config;
    },
  },
  globalCss: {
    html: {
      colorPalette: "mori",
      // Chlorophyll はライトテーマのみなので、OS がダークでもライトで表示する
      colorScheme: "light",
    },
    body: {
      bg: "colorPalette.bg",
      color: "fg",
      textStyle: "sm",
      WebkitFontSmoothing: "antialiased",
    },
  },
});
