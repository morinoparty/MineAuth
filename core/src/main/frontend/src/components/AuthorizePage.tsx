import { css } from "styled-system/css";
import type { Messages } from "../i18n";
import type { AuthorizeModel } from "../model";
import { ClientInfo } from "./ClientInfo";
import { LoginForm } from "./LoginForm";
import { PageShell } from "./PageShell";

interface AuthorizePageProps {
  model: AuthorizeModel;
  messages: Messages;
}

/**
 * OAuth2 認可画面。
 * 左の色付きパネルにクライアントと要求スコープ、右にログインフォームを並べる（狭い画面では縦に積む）
 */
export const AuthorizePage = ({ model, messages }: AuthorizePageProps) => (
  <PageShell>
    <div className={css({ display: "grid", gridTemplateColumns: { base: "1fr", md: "1fr 1fr" } })}>
      <section
        className={css({
          bg: "colorPalette.2",
          borderBottomWidth: { base: "1px", md: "0" },
          borderEndWidth: { base: "0", md: "1px" },
          borderColor: "border.subtle",
          p: { base: "6", md: "14" },
        })}
      >
        <ClientInfo model={model} messages={messages} />
      </section>
      <section className={css({ p: { base: "6", md: "14" }, display: "flex", alignItems: "center" })}>
        <LoginForm model={model} messages={messages} />
      </section>
    </div>
  </PageShell>
);
