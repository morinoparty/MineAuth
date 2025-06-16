import "@typespec/http";
import "@typespec/openapi";

using TypeSpec.Http;
using TypeSpec.OpenAPI;

namespace MineAuth.Models.Plugins;

/**
 * 送金データモデル
 * Vaultプラグインでの送金情報
 */
@doc("送金データモデル")
model RemittanceData {
  /** 送金先のプレイヤー名 */
  @example("Notch")
  target: string;

  /** 送金額 */
  @example(250.0)
  amount: float64;
}

/**
 * 残高レスポンスモデル
 * 現在の残高情報
 */
@doc("残高レスポンスモデル")
model BalanceResponse {
  /** 現在の残高 */
  @example(1000.0)
  balance: float64;
}

/**
 * 送金レスポンスモデル
 * 送金結果の成功メッセージ
 */
@doc("送金レスポンスモデル")
model SendMoneyResponse {
  /** 送金完了メッセージ */
  @example("Successfully sent 250 to Notch. Your balance is 750")
  status: string;
}

/**
 * エラーレスポンスモデル
 * 送金処理の失敗メッセージ
 */
@doc("エラーレスポンスモデル")
model ErrorResponse {
  /** エラーメッセージ */
  @example("Not enough money")
  error: string;
}
