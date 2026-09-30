import { css } from "styled-system/css";

/**
 * Chlorophyll の Field は sm / md までなので、認可画面では入力欄とラベルをひと回り大きくする
 */
export const largeInput = css({ height: "14", px: "4", textStyle: "lg" });

/** 大きい入力欄に合わせたラベル */
export const largeLabel = css({ textStyle: "lg" });
