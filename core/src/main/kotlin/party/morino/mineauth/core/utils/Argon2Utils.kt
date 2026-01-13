package party.morino.mineauth.core.utils

import com.password4j.Argon2Function
import com.password4j.Password
import com.password4j.types.Argon2

/**
 * Argon2idハッシュ化ユーティリティ
 * OWASPが推奨するArgon2idアルゴリズムを使用してパスワード/シークレットをハッシュ化する
 */
object Argon2Utils {
    // OWASP推奨パラメータ（最小要件）
    // https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html
    private val argon2Function = Argon2Function.getInstance(
        19456,  // メモリ: 19MB（OWASP最小推奨）
        2,      // イテレーション: 2
        1,      // 並列度: 1
        32,     // ハッシュ長: 32バイト
        Argon2.ID // Argon2id（サイドチャネル攻撃とGPU攻撃の両方に耐性）
    )

    /**
     * クライアントシークレットをArgon2idでハッシュ化する
     *
     * @param secret ハッシュ化する平文のシークレット
     * @return Argon2idハッシュ文字列（ソルト含む）
     */
    fun hashSecret(secret: String): String {
        return Password.hash(secret)
            .addRandomSalt()
            .with(argon2Function)
            .result
    }

    /**
     * シークレットがハッシュと一致するかを検証する
     * タイミング攻撃に対して定数時間で比較を行う
     *
     * @param secret 検証する平文のシークレット
     * @param hashedSecret 保存されているArgon2idハッシュ
     * @return 一致する場合true
     */
    fun verifySecret(secret: String, hashedSecret: String): Boolean {
        return Password.check(secret, hashedSecret)
            .with(argon2Function)
    }
}
