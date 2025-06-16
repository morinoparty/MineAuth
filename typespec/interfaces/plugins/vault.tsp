import "@typespec/http";
import "@typespec/openapi";
import "../../models/plugins.tsp";
import "../../security/oauth2.tsp";

using TypeSpec.Http;
using TypeSpec.OpenAPI;
using MineAuth.Models.Plugins;
using MineAuth.Security;

namespace MineAuth.Interfaces.PluginIntegrations;

/**
 * Vault API
 * Vaultプラグイン（経済プラグイン）との統合API
 */
@route("/api/v1/plugins/vault")
@tag("Vault")
interface Vault {
  /**
   * 残高確認
   * 認証ユーザーの残高情報を取得
   */
  @route("/balance/me")
  @get
  @useAuth(AllAuth)
  getBalance(): BalanceResponse;

  /**
   * 送金処理
   * 指定したプレイヤーに対して送金を行う
   */
  @route("/send")
  @post
  @useAuth(AllAuth)
  op sendMoney(@body body: RemittanceData): {
      @statusCode statusCode: 200;
      @body body: SendMoneyResponse;
  } | {
   @statusCode statusCode: 400;
      @body body: ErrorResponse;
  };
}
