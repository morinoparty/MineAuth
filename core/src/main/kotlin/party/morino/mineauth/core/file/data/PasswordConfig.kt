package party.morino.mineauth.core.file.data

import kotlinx.serialization.Serializable

/**
 * パスワードハッシュ設定
 * Argon2のパラメータはハッシュ文字列に含まれるため、変更しても既存のパスワードは検証できる
 * 変更後は各プレイヤーの次回ログイン時に新しいパラメータで再ハッシュされる
 */
@Serializable
data class PasswordConfig(
    // ハッシュ化の際にパスワードへ付与するpepper
    // nullの場合は起動時にランダム生成してconfig.jsonへ保存する
    // 変更すると既存のパスワードで一切ログインできなくなるため注意
    val pepper: String? = null,

    // Argon2idのメモリ使用量（KiB、OWASP推奨の最小値は19456）
    val memory: Int = 19456,

    // Argon2idのイテレーション回数
    val iterations: Int = 2,

    // Argon2idの並列度
    val parallelism: Int = 1,

    // ハッシュ長（バイト）
    val hashLength: Int = 32,

    // ソルト長（バイト）
    val saltLength: Int = 64
)
