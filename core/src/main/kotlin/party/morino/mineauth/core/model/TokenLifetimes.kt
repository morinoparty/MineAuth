package party.morino.mineauth.core.model

import java.time.Duration

/**
 * トークンと失効レコードの有効期間
 *
 * リフレッシュトークンの発行と失効レコードの保持期限で同じ値を参照し、両者が食い違わないようにする
 */
object TokenLifetimes {
    // リフレッシュトークンの有効期間
    val REFRESH_TOKEN: Duration = Duration.ofDays(30)

    // 対象のトークンが検証を通らなくなった後も、失効レコードを残しておく期間
    // 次の揺らぎを十分に上回る長さにして、レコードの削除で失効済みトークンが有効に戻らないようにする
    // - Bearer検証が有効期限後も受け付ける猶予（TokenVerifiersの3秒）
    // - 失効の書き込みと同時に進行中のローテーションが、書き込みの直後に発行する新トークン
    // - 複数サーバー間の時刻ずれと、期限をローカル時刻で保存することによる夏時間の切り替え
    val REVOCATION_RECORD_MARGIN: Duration = Duration.ofDays(1)
}
