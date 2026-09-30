import { useState } from "react";
import { css } from "styled-system/css";
import { Button, Field } from "../chlorophyll";
import type { Messages } from "../i18n";
import type { AuthorizeModel } from "../model";
import { largeInput, largeLabel } from "./fieldSize";
import { PasswordField } from "./PasswordField";

interface LoginFormProps {
  model: AuthorizeModel;
  messages: Messages;
}

/**
 * ログインして認可するフォーム。
 * POST /authorize が redirect_uri へ 302 で返すことで OAuth のフローが進むため、
 * fetch ではなくブラウザのネイティブなフォーム送信で現在の URL へ POST する
 */
export const LoginForm = ({ model, messages }: LoginFormProps) => {
  // 二重送信を防ぐため、送信後はボタンを無効にする
  const [submitting, setSubmitting] = useState(false);

  return (
    <form method="post" onSubmit={() => setSubmitting(true)} className={css({ width: "full", display: "flex", flexDirection: "column", gap: "6" })}>
      <div className={css({ display: "flex", flexDirection: "column", gap: "2", mb: "2" })}>
        <h2 className={css({ textStyle: { base: "3xl", md: "4xl" }, fontWeight: "bold", color: "colorPalette.12" })}>{messages.signIn}</h2>
        <p className={css({ textStyle: "lg", color: "fg.muted" })}>{messages.signInDescription}</p>
      </div>

      {/* 認可リクエストのパラメータをそのまま送り返す（フィールド名は POST /authorize の仕様どおり） */}
      <input type="hidden" name="client_id" value={model.clientId} />
      <input type="hidden" name="redirect_uri" value={model.redirectUri} />
      <input type="hidden" name="response_type" value={model.responseType} />
      <input type="hidden" name="state" value={model.state} />
      <input type="hidden" name="scope" value={model.scope} />
      <input type="hidden" name="code_challenge" value={model.codeChallenge} />
      <input type="hidden" name="code_challenge_method" value={model.codeChallengeMethod} />
      {model.nonce !== undefined && <input type="hidden" name="nonce" value={model.nonce} />}

      <Field.Root required>
        <Field.Label className={largeLabel}>{messages.usernameLabel}</Field.Label>
        <Field.Input name="username" type="text" autoComplete="username" placeholder="Steve" className={largeInput} />
      </Field.Root>
      <PasswordField messages={messages} />

      <Button type="submit" intent="primary" size="lg" disabled={submitting} className={css({ width: "full", height: "14", mt: "2", textStyle: "lg" })}>
        {messages.authorize}
      </Button>
      {/* パスワードはゲーム内のコマンドで発行するため、未登録のプレイヤー向けに案内する */}
      <p className={css({ textStyle: "md", color: "fg.muted", textAlign: "center" })}>
        {messages.passwordHint} <code className={css({ fontFamily: "mono", color: "colorPalette.fg" })}>/ma register</code>
      </p>
    </form>
  );
};
