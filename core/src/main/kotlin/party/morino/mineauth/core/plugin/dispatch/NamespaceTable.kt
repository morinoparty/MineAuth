package party.morino.mineauth.core.plugin.dispatch

import party.morino.mineauth.core.plugin.annotation.EndpointMetadata

/**
 * 1つの名前空間に登録されたエンドポイント群を保持するテーブル
 * ディスパッチャのConcurrentHashMapの値としてアトミックに差し替えられる
 *
 * @property pluginName 名前空間を所有するプラグイン名
 * @property basePath マウントされたベースパス（例: /api/v1/plugins/vault）
 * @property endpoints コンパイル済みエンドポイントのリスト
 */
data class NamespaceTable(
    val pluginName: String,
    val basePath: String,
    val endpoints: List<EndpointMetadata>
) {
    /**
     * セグメント数 -> そのセグメント数のエンドポイント（具体性の高い順）
     *
     * リクエストのセグメント数と一致しないエンドポイントは絶対にマッチしないため、
     * ディスパッチ時は該当バケットのみを走査すればよい。
     * バケット内は具体性の降順に安定ソート済みなので、先頭からの最初の一致が
     * 「リテラルが多いルート優先、同点なら登録順」という従来の選択規則と一致する。
     */
    private val endpointsBySegmentCount: Map<Int, List<EndpointMetadata>> = endpoints
        .groupBy { it.pathSegments.size }
        .mapValues { (_, bucket) -> bucket.sortedByDescending { it.specificity } }

    /**
     * 指定したセグメント数にマッチしうるエンドポイントを具体性の高い順に返す
     *
     * @param segmentCount リクエストパスのセグメント数
     * @return 候補エンドポイント（該当なしなら空リスト）
     */
    fun candidates(segmentCount: Int): List<EndpointMetadata> =
        endpointsBySegmentCount[segmentCount] ?: emptyList()
}
