package party.morino.mineauth.core.web.ratelimit

// 保持するキーの既定の上限（1キーあたりは小さいので、複数のカウンターを合わせてもメモリを圧迫しない）
internal const val DEFAULT_MAX_KEYS = 10_000

/**
 * 上限を超えると最も長く使われていないキーから捨てるマップ
 *
 * キーはリクエスト由来で攻撃者がいくらでも増やせるため、上限を設けてメモリ使用量を抑える。
 * スレッドセーフではないので、利用側がロックで保護すること
 *
 * @param maxKeys 保持するキーの上限
 */
internal class LruMap<V>(private val maxKeys: Int) :
    LinkedHashMap<String, V>(INITIAL_CAPACITY, LOAD_FACTOR, ACCESS_ORDER) {

    /**
     * 追加のたびにLinkedHashMapから呼ばれ、trueを返すと最も古いキーが削除される
     *
     * @param eldest 最も長く使われていないエントリ
     * @return 上限を超えている場合はtrue
     */
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean = size > maxKeys

    private companion object {
        const val INITIAL_CAPACITY = 16
        const val LOAD_FACTOR = 0.75f

        // 挿入順ではなくアクセス順に並べ、先頭が「最も長く使われていないキー」になるようにする
        const val ACCESS_ORDER = true
    }
}
