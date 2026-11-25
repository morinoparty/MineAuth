package party.morino.mineauth.addons.quickshop.stock

import java.util.UUID

/**
 * ショップのあるチャンクの識別子
 *
 * @property worldId ワールドのUUID
 * @property x チャンクのX座標（ブロック座標 shr 4）
 * @property z チャンクのZ座標（ブロック座標 shr 4）
 */
data class ChunkKey(val worldId: UUID, val x: Int, val z: Int) {
    companion object {
        /**
         * 1 リクエストで読み込むチャンクを選ぶ
         *
         * 並び順（ページ内のショップの順）で先に出てくるチャンクから、重複を除いて最大 [maxLoads] 個を選ぶ。
         * 選ばれなかったチャンクのショップは、次のリクエスト以降で数える。
         *
         * @param chunks 読み込まれていないショップのチャンク（ショップの順、重複あり）
         * @param maxLoads 読み込むチャンクの最大数（0 なら読み込まない）
         * @return 読み込むチャンク
         */
        fun selectToLoad(chunks: List<ChunkKey>, maxLoads: Int): Set<ChunkKey> =
            chunks.distinct().take(maxLoads.coerceAtLeast(0)).toSet()
    }
}
