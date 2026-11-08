package party.morino.mineauth.core.repository

import kotlin.coroutines.cancellation.CancellationException
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 失効レコードの定期クリーンアップの1回分の処理
 *
 * JWTの失効はブラックリストで管理するため、対象のトークンが検証を通らなくなったレコードを消さないとテーブルが増え続ける。
 * 片方のテーブルの削除に失敗しても、もう片方の削除と次回以降の実行は止めない
 *
 * タイマーの起動はプラグイン本体（MineAuth）が行う。ここにタイマーを持たせないのは、テストで起動させないためである
 *
 * @param logger 結果と失敗を出力するロガー
 */
class RevocationRecordCleanup(private val logger: Logger) {

    /**
     * 期限を過ぎた失効トークンと失効グラントのレコードを削除する
     */
    suspend fun run() {
        cleanup("revoked_tokens") { RevokedTokenRepository.cleanupExpired() }
        cleanup("revoked_grants") { RevokedGrantRepository.cleanupExpired() }
    }

    /**
     * 1つのテーブルをクリーンアップする
     *
     * タイマーの境界にあたるため、種類を問わず記録して次のテーブルと次回の実行へ進む
     *
     * @param table ログに記録するテーブル名
     * @param delete 削除処理（削除件数を返す）
     */
    private suspend fun cleanup(table: String, delete: suspend () -> Int) {
        try {
            val removed = delete()
            if (removed > 0) {
                logger.info("Removed $removed expired record(s) from $table")
            }
        } catch (e: CancellationException) {
            // コルーチンのキャンセルは再送出する（握り潰してはいけない）
            throw e
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Failed to remove expired records from $table", e)
        }
    }
}
