/** 対応している表示言語 */
export type Locale = "en" | "ja";

/** 画面の文言 */
interface Messages {
  authorizeApp: string;
  requestingAccess: string;
  issuerLabel: string;
  permissionsLabel: string;
  signIn: string;
  signInDescription: string;
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
    authorizeApp: "Authorize Application",
    requestingAccess: "is requesting access to your account.",
    issuerLabel: "Issuer",
    permissionsLabel: "Requested permissions",
    signIn: "Sign in",
    signInDescription: "Enter your Minecraft username and MineAuth password.",
    passwordHint: "No password yet? Run in game:",
    usernameLabel: "Username",
    passwordLabel: "Password",
    showPassword: "Show password",
    hidePassword: "Hide password",
    authorize: "Authorize",
    loadError: "Failed to load the authorization request. Please start over from the application.",
    scopes: {
      openid: "Basic identity information access",
      profile: "Profile information (name, avatar) access",
      email: "Email address access",
      roles: "Permission group information access",
    },
  },
  ja: {
    authorizeApp: "アプリケーションの認可",
    requestingAccess: "があなたのアカウントへのアクセスを要求しています。",
    issuerLabel: "発行者",
    permissionsLabel: "要求されている権限",
    signIn: "サインイン",
    signInDescription: "Minecraft のユーザー名と MineAuth のパスワードを入力してください。",
    passwordHint: "パスワードはゲーム内で発行できます:",
    usernameLabel: "ユーザー名",
    passwordLabel: "パスワード",
    showPassword: "パスワードを表示",
    hidePassword: "パスワードを隠す",
    authorize: "認可する",
    loadError: "認可リクエストを読み込めませんでした。アプリケーションからやり直してください。",
    scopes: {
      openid: "基本的な識別情報へのアクセス",
      profile: "プロフィール情報（名前、アバター）へのアクセス",
      email: "メールアドレスへのアクセス",
      roles: "権限グループ情報へのアクセス",
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
