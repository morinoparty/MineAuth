import { css } from "styled-system/css";

/**
 * Chlorophyll の Field は sm / md までなので、認可画面では入力欄をひと回り大きくする
 */
export const largeInput = css({ height: "12", px: "3.5", textStyle: "md" });

/** 入力欄のラベル */
export const fieldLabel = css({ textStyle: "sm", fontWeight: "medium" });
