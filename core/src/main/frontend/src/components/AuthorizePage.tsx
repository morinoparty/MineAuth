import { css } from "styled-system/css";
import { Separator } from "../chlorophyll";
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
 * 左にクライアントと要求スコープ、右にログインフォームを並べる（狭い画面では縦に積む）
 */
export const AuthorizePage = ({ model, messages }: AuthorizePageProps) => (
  <PageShell logoUrl={model.logoUrl} applicationName={model.applicationName}>
    <div className={css({ display: "flex", flexDirection: { base: "column", md: "row" } })}>
      <div className={css({ flex: "1", p: "6" })}>
        <ClientInfo model={model} messages={messages} />
      </div>
      {/* 縦並びのときは横線、横並びのときは縦線で区切る */}
      <Separator className={css({ display: { base: "block", md: "none" } })} />
      <Separator orientation="vertical" className={css({ display: { base: "none", md: "block" } })} />
      <div className={css({ flex: "1", p: "6" })}>
        <LoginForm model={model} messages={messages} />
      </div>
    </div>
  </PageShell>
);
