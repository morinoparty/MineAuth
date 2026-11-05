import { css } from "styled-system/css";
import type { Messages } from "../i18n";

interface LoadErrorProps {
  messages: Messages;
}

/** ビューモデルを読み込めなかったときの表示 */
export const LoadError = ({ messages }: LoadErrorProps) => (
  <main className={css({ minHeight: "100vh", display: "flex", alignItems: "center", justifyContent: "center", p: "4" })}>
    <p role="alert" className={css({ color: "fg.error" })}>
      {messages.loadError}
    </p>
  </main>
);
