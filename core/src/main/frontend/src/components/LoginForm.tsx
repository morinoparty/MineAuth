import { useState } from "react";
import { css } from "styled-system/css";
import { Button } from "../chlorophyll";
import type { Messages } from "../i18n";
import type { AuthorizeModel } from "../model";
import { inputStyle, labelStyle } from "./fieldStyles";
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
    <form method="post" onSubmit={() => setSubmitting(true)} className={css({ display: "flex", flexDirection: "column", gap: "4" })}>
      <h2 className={css({ textStyle: "lg", fontWeight: "semibold", color: "colorPalette.fg", textAlign: "center", mb: "2" })}>
        {messages.signIn}
      </h2>

      {/* 認可リクエストのパラメータをそのまま送り返す（フィールド名は POST /authorize の仕様どおり） */}
      <input type="hidden" name="client_id" value={model.clientId} />
      <input type="hidden" name="redirect_uri" value={model.redirectUri} />
      <input type="hidden" name="response_type" value={model.responseType} />
      <input type="hidden" name="state" value={model.state} />
      <input type="hidden" name="scope" value={model.scope} />
      <input type="hidden" name="code_challenge" value={model.codeChallenge} />
      <input type="hidden" name="code_challenge_method" value={model.codeChallengeMethod} />
      {model.nonce !== undefined && <input type="hidden" name="nonce" value={model.nonce} />}

      <div>
        <label htmlFor="username" className={labelStyle}>
          {messages.usernameLabel}
        </label>
        <input id="username" name="username" type="text" autoComplete="username" placeholder="username" required className={inputStyle} />
      </div>
      <PasswordField messages={messages} />

      <Button type="submit" intent="primary" size="md" disabled={submitting} className={css({ width: "full", mt: "2" })}>
        {messages.authorize}
      </Button>
    </form>
  );
};
