package party.morino.mineauth.core.web.telemetry

import io.ktor.server.request.queryString
import io.opentelemetry.instrumentation.ktor.v3_0.KtorServerTelemetryBuilder
import io.opentelemetry.semconv.UrlAttributes

/**
 * HTTPサーバースパンのURL属性から、識別子や秘密情報を取り除くためのヘルパー
 *
 * 背景:
 * KtorServerTelemetryはurl.pathとurl.queryにリクエストの値をそのまま記録する。
 * url.pathにはプレイヤー名などのPIIが入り、url.queryには
 * GET /oauth2/end_session のid_token_hint（名前やメールアドレスを含みうる署名済みのIDトークン）や、
 * GET /oauth2/authorize のstate・nonce・code_challengeが入るため、そのままOTLPのバックエンドへ送らない。
 */

// url.queryで値の代わりに記録する文字列
private const val REDACTED_QUERY_VALUE = "REDACTED"

/**
 * クエリ文字列のパラメーター名だけを残し、値をすべて伏せる
 *
 * 例: `id_token_hint=eyJ...&state=abc` → `id_token_hint=REDACTED&state=REDACTED`
 * どのパラメーターが送られたかはトレースで追えるようにし、値は1文字も残さない。
 * 値を持たないパラメーター（`=`のないもの）は伏せる値がないため、そのまま残す。
 *
 * @param rawQuery `?`より後ろのクエリ文字列（パーセントエンコードされたまま）
 * @return 値をREDACTED_QUERY_VALUEに置き換えたクエリ文字列
 */
fun redactQueryValues(rawQuery: String): String =
    rawQuery.split('&').joinToString("&") { parameter ->
        // 値に=が含まれていても、最初の=より後ろはすべて値として扱う
        if ('=' in parameter) "${parameter.substringBefore('=')}=$REDACTED_QUERY_VALUE" else parameter
    }

/**
 * KtorServerTelemetryがスパン終了時に記録するurl.pathとurl.queryを置き換える
 *
 * - url.path: 算出済みのルートテンプレート（OTEL_ROUTE_TEMPLATE）で置き換え、具体パスのPIIを除く
 * - url.query: redactQueryValuesで値を伏せる。ルートによらずすべてのリクエストに適用する
 *
 * onEndで設定した属性は、ライブラリがスパン開始時に設定した同名の属性を上書きする。
 *
 * @receiver KtorServerTelemetryの設定
 */
fun KtorServerTelemetryBuilder.redactUrlAttributes() {
    attributesExtractor {
        onEnd {
            request.call.attributes.getOrNull(OTEL_ROUTE_TEMPLATE)?.let { template ->
                attributes.put(UrlAttributes.URL_PATH, template)
            }
            // クエリがないリクエストではライブラリが記録する値も空文字なので、置き換えない
            request.queryString().takeIf { it.isNotEmpty() }?.let { query ->
                attributes.put(UrlAttributes.URL_QUERY, redactQueryValues(query))
            }
        }
    }
}
