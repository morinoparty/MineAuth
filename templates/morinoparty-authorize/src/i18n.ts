/** 対応している表示言語 */
export type Locale = "en" | "ja";

/** 画面の文言 */
interface Messages {
  requestingAccess: string;
  permissionsLabel: string;
  signIn: string;
  passwordHint: string;
  usernameLabel: string;
  passwordLabel: string;
  showPassword: string;
  hidePassword: string;
  authorize: string;
  loadError: string;
  scopes: Record<string, string>;
}

const messages: Record<Locale, Messages> = {
  en: {
    requestingAccess: "is requesting access to your account.",
    permissionsLabel: "Requested permissions",
    signIn: "Sign in",
    passwordHint: "No password yet? Run in game:",
    usernameLabel: "Username",
    passwordLabel: "Password",
    showPassword: "Show password",
    hidePassword: "Hide password",
    authorize: "Authorize",
    loadError: "Failed to load the authorization request. Please start over from the application.",
    scopes: {
      openid: "Basic identity information access",
      profile: "Profile information (username, avatar) access",
      email: "Email address access",
      roles: "Permission group information access",
      plugin: "Access to data held by each plugin",
    },
  },
  ja: {
    requestingAccess: "があなたのアカウントへのアクセスを要求しています。",
    permissionsLabel: "要求されている権限",
    signIn: "サインイン",
    passwordHint: "パスワードはゲーム内で発行できます:",
    usernameLabel: "ユーザー名",
    passwordLabel: "パスワード",
    showPassword: "パスワードを表示",
    hidePassword: "パスワードを隠す",
    authorize: "認可する",
    loadError: "認可リクエストを読み込めませんでした。アプリケーションからやり直してください。",
    scopes: {
      openid: "基本的な識別情報へのアクセス",
      profile: "プロフィール情報（ユーザー名、アバター）へのアクセス",
      email: "メールアドレスへのアクセス",
      roles: "権限グループ情報へのアクセス",
      plugin: "各プラグインが持つ情報へのアクセス",
    },
  },
};

/** ブラウザの言語設定から表示言語を決める。未対応の言語は英語にする */
export const detectLocale = (): Locale => (navigator.language.slice(0, 2) === "ja" ? "ja" : "en");

/** 表示言語の文言を返す */
export const getMessages = (locale: Locale): Messages => messages[locale];

/** スコープの説明を返す。未知のスコープはスコープ名をそのまま表示する */
export const describeScope = (m: Messages, scope: string): string => m.scopes[scope] ?? scope;

export type { Messages };
