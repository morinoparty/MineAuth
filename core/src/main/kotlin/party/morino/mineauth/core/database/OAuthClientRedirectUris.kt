package party.morino.mineauth.core.database

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * OAuthクライアントのリダイレクトURIテーブル
 * 1つのクライアントに複数のリダイレクトURIを登録できるようにする
 */
object OAuthClientRedirectUris : Table("oauth_client_redirect_uris") {
    // 自動採番ID（URIは最大2048文字でインデックスに収まらないため、主キーには使わない）
    val id = long("id").autoIncrement()

    // クライアントID（クライアント削除時にURIも削除する）
    val clientId = varchar("client_id", 36)
        .references(OAuthClients.clientId, onDelete = ReferenceOption.CASCADE)

    // 登録順（表示順を安定させるため。0始まり）
    val position = integer("position")

    // リダイレクトURI（正規表現パターンをサポート）
    val redirectUri = varchar("redirect_uri", 2048)

    override val primaryKey = PrimaryKey(id)

    init {
        // 同じクライアント・同じ位置の重複登録を防ぐ（複数サーバーが同時にマイグレーションした場合の二重コピー対策）
        uniqueIndex(clientId, position)
    }
}
