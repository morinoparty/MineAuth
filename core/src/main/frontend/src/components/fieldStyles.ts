import { css } from "styled-system/css";

/**
 * テキスト入力欄の見た目。
 * Chlorophyll には入力欄のコンポーネントが無いため、Select のトリガーと同じ「フォームコントロール」の見た目に揃える
 */
export const inputStyle = css({
  width: "full",
  height: "control.md",
  px: "3",
  bg: "bg.panel",
  borderWidth: "1px",
  borderStyle: "solid",
  borderColor: "border.subtle",
  borderRadius: "control",
  color: "colorPalette.fg",
  transitionDuration: "fast",
  transitionProperty: "border-color, box-shadow",
  transitionTimingFunction: "easeInOut",
  _hover: { borderColor: "border.interactive" },
  _placeholder: { color: "fg.placeholder" },
  _focusVisible: {
    outlineStyle: "solid",
    outlineWidth: "focus.ring",
    outlineColor: "colorPalette.focus.ring",
    outlineOffset: "focus.ring.offset",
  },
});

/** 入力欄のラベル */
export const labelStyle = css({
  display: "block",
  mb: "2",
  fontWeight: "medium",
  color: "colorPalette.fg",
});
