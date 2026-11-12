// Panda CSS の PostCSS プラグイン。src/index.css の @layer 宣言に、panda.config.ts から生成した CSS を差し込む
module.exports = {
  plugins: {
    "@pandacss/dev/postcss": {},
  },
};
