import "@typespec/http";
import "@typespec/openapi";
import "../models/common.tsp";

using TypeSpec.Http;
using TypeSpec.OpenAPI;
using MineAuth.Models;

namespace MineAuth.Interfaces;

/**
 * サーバーAPI
 * サーバー関連の情報にアクセスするエンドポイント
 */
@route("/api/v1/commons/server")
@tag("Server")
interface Server {
  /**
   * オンラインプレイヤー一覧取得
   * 現在サーバーに接続しているプレイヤーの一覧を取得
   */
  @route("/players")
  @get
  getPlayers(): {
    /** プレイヤーID */
    ...UUID;

    /** プレイヤー名 */
    @example("Notch")
    name: string;
  }[];
}
