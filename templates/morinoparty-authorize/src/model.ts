/**
 * GET /authorize が HTML に埋め込むビューモデル。
 * Kotlin 側の AuthorizePageModel と同じ形を保つこと
 */
export interface AuthorizeModel {
  clientId: string;
  clientName: string;
  redirectUri: string;
  responseType: string;
  state: string;
  /** 正規化済みのスコープ文字列（hidden フィールドでそのまま送り返す） */
  scope: string;
  /** 表示用に分割したスコープ */
  scopeList: string[];
  issuer: string;
  codeChallenge: string;
  codeChallengeMethod: string;
  logoUrl: string;
  applicationName: string;
  /** OIDC の nonce。リクエストに含まれていた場合のみ存在する */
  nonce?: string;
}

/** vite dev で画面を確認するためのサンプル値 */
const devSample: AuthorizeModel = {
  clientId: "sample-client",
  clientName: "Sample Application",
  redirectUri: "http://localhost:3000/callback",
  responseType: "code",
  state: "sample-state",
  scope: "openid profile email",
  scopeList: ["openid", "profile", "email"],
  issuer: "http://localhost:8080",
  codeChallenge: "sample-challenge",
  codeChallengeMethod: "S256",
  logoUrl: "https://github.com/morinoparty.png",
  applicationName: "MineAuth",
};

/**
 * HTML に埋め込まれた JSON からビューモデルを読み込む。
 * 読み込めない場合は null を返し、呼び出し側でエラー表示にする
 */
export const readModel = (): AuthorizeModel | null => {
  const element = document.getElementById("mineauth-model");
  try {
    return JSON.parse(element?.textContent ?? "") as AuthorizeModel;
  } catch {
    // 開発サーバーではプレースホルダのままなので、サンプル値で描画する
    return import.meta.env.DEV ? devSample : null;
  }
};
