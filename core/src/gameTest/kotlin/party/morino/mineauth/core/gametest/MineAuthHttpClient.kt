package party.morino.mineauth.core.gametest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

/**
 * ゲームテストから MineAuth の HTTP サーバーへ JSON を取りに行くクライアント
 *
 * MineAuth の HTTP サーバーはプラグインの有効化の後に非同期で起動し、/mineauth reload でも一度止まる。
 * そのため、200 が返るまで待つ awaitJson だけを用意する。
 *
 * @property baseUrl MineAuth の server.baseUrl
 */
class MineAuthHttpClient(private val baseUrl: String) {
    // 接続できないときは早めに諦めて、次の試行に回す
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT.toJavaDuration()).build()

    /**
     * path に GET し、200 が返るまで待って JSON オブジェクトとして読む
     *
     * @param path baseUrl からのパス（例: /.well-known/openid-configuration）
     * @param timeout 待つ時間
     * @return レスポンスの JSON オブジェクト
     * @throws AssertionError timeout までに 200 が返らなかった場合
     */
    suspend fun awaitJson(path: String, timeout: Duration = DEFAULT_TIMEOUT): JsonObject {
        val deadline = TimeSource.Monotonic.markNow() + timeout
        while (true) {
            val body = getOrNull(path)
            if (body != null) return Json.parseToJsonElement(body).jsonObject
            // 期限を過ぎたらテストの失敗にする（最後の試行の後に判定し、期限ちょうどの応答も受け取る）
            if (deadline.hasPassedNow()) throw AssertionError("GET $baseUrl$path did not return 200 within $timeout")
            delay(POLL_INTERVAL)
        }
    }

    /**
     * path に 1 回だけ GET する
     *
     * @param path baseUrl からのパス
     * @return 200 のときの本文。それ以外のステータスか、接続できなかった場合は null
     */
    private suspend fun getOrNull(path: String): String? = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(REQUEST_TIMEOUT.toJavaDuration())
            .GET()
            .build()
        try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
                .takeIf { it.statusCode() == HTTP_OK }
                ?.body()
        } catch (e: IOException) {
            // 起動前や再起動の途中は接続を拒否されるので、呼び出し側でもう一度試す
            null
        }
    }

    private companion object {
        // 成功とみなすステータスコード
        const val HTTP_OK = 200

        // Paper が libraries を取得した後に HTTP サーバーが起動するまでの余裕を見た既定の待ち時間
        val DEFAULT_TIMEOUT = 60.seconds

        // 1 回のリクエストの待ち時間
        val REQUEST_TIMEOUT = 5.seconds

        // 再試行の間隔
        val POLL_INTERVAL = 500.milliseconds
    }
}
