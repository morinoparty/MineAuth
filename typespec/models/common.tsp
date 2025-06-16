import "@typespec/http";
import "@typespec/openapi";

using TypeSpec.Http;
using TypeSpec.OpenAPI;

namespace MineAuth.Models;

/**
 * UUIDモデル
 * プレイヤーの一意識別子
 */
@doc("UUIDモデル")
model UUID {
  @pattern("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
  @example("069a79f4-44e9-4726-a5be-fca90e38aaf5")
  id: string;
}

/**
 * プロフィールデータモデル
 * ユーザープロフィール情報を格納
 */
@doc("プロフィールデータモデル")
model ProfileData {
  /** ユーザー名 */
  @example("Notch")
  username: string;

  /** プレイヤーUUID */
  ...UUID;
}
