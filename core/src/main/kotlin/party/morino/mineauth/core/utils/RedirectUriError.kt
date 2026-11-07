package party.morino.mineauth.core.utils

/**
 * リダイレクトURIを登録できない理由
 * ダイアログとコマンドが同じ文言で拒否理由を表示できるよう、メッセージを型と一緒に持つ
 */
sealed interface RedirectUriError {
    // 登録者に表示する拒否理由
    val message: String

    // 1件も入力されていない
    data object Empty : RedirectUriError {
        override val message = "リダイレクトURIを入力してください"
    }

    // 登録できる件数を超えている
    data class TooMany(val limit: Int) : RedirectUriError {
        override val message = "リダイレクトURIは${limit}件まで登録できます"
    }

    // 1件の長さが上限を超えている（長大な入力をそのまま表示しないようURIは含めない）
    data class TooLong(val limit: Int) : RedirectUriError {
        override val message = "リダイレクトURIは${limit}文字以内で入力してください"
    }

    // URIとしても、受け付ける正規表現パターンとしても解釈できない
    data class InvalidFormat(val uri: String) : RedirectUriError {
        override val message = "無効なリダイレクトURI形式です（https:// で始まるURIまたは正規表現パターンを入力してください）: $uri"
    }

    // RFC 6749 Section 3.1.2: リダイレクトURIはフラグメントを含んではならない
    data class FragmentNotAllowed(val uri: String) : RedirectUriError {
        override val message = "リダイレクトURIにフラグメント（#）は使用できません: $uri"
    }

    // RFC 9700 Section 2.6: ループバック以外へのhttpは認可コードが平文で流れるため許可しない
    data class InsecureHttp(val uri: String) : RedirectUriError {
        override val message = "httpはループバックホスト（localhost, 127.0.0.1, [::1]）にのみ使用できます。httpsを使用してください: $uri"
    }

    // httpの正規表現パターンや、正規表現として評価すると別ホストに一致してしまうhttpのURI
    data class UnsafeLoopbackPattern(val uri: String) : RedirectUriError {
        override val message = "httpの正規表現パターンはホストをループバックに固定し、ポートとパスの区切りも固定してください" +
            "（例: http://localhost:\\d+/callback, http://127\\.0\\.0\\.1:\\d+/callback）: $uri"
    }

    // ユーザー情報を含むhttpのURI（正規表現として評価すると、@より前に書いたパターンで別ホストに一致してしまう）
    data class HttpUserInfo(val uri: String) : RedirectUriError {
        override val message = "httpのリダイレクトURIにはユーザー情報（ホストの前の「...@」の部分）を含められません" +
            "（例: http://localhost:3000/callback）: $uri"
    }

    // スクリプト実行やローカルファイル参照につながるため、常に拒否するスキーム
    data class ForbiddenScheme(val uri: String, val scheme: String) : RedirectUriError {
        override val message = "${scheme}スキームはリダイレクトURIに使用できません: $uri"
    }

    // RFC 8252 Section 7.1: カスタムスキームは逆ドメイン形式のものだけを意図的に許可する
    data class UnsupportedScheme(val uri: String, val scheme: String) : RedirectUriError {
        override val message = "${scheme}スキームは使用できません。カスタムスキームは逆ドメイン形式" +
            "（例: com.example.app://callback）で指定してください: $uri"
    }

    // リダイレクト時に書き換えられてしまう形式のカスタムスキーム
    data class UnsupportedPrivateUseForm(val uri: String) : RedirectUriError {
        override val message = "カスタムスキームは小文字で「スキーム://ホスト」の形式" +
            "（例: com.example.app://callback）で指定してください: $uri"
    }

    // 先頭階層の選択（|）は、以降の選択肢がスキームの制限を受けなくなるため許可しない
    data class TopLevelAlternation(val uri: String) : RedirectUriError {
        override val message = "正規表現パターンの先頭階層で | は使用できません。" +
            "選択肢はグループ内に書いてください（例: https://(a|b)\\.example\\.com/callback）: $uri"
    }
}
