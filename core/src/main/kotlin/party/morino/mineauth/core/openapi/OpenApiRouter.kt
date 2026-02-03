package party.morino.mineauth.core.openapi

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.java.KoinJavaComponent.get
import party.morino.mineauth.core.openapi.generator.OpenApiGenerator

/**
 * OpenAPI関連のエンドポイントを提供するルーター
 * /openapi: OpenAPI仕様をJSON形式で返す
 * /scalar: Scalar UIでAPIドキュメントを表示
 */
object OpenApiRouter {

    /**
     * OpenAPI関連のルートを設定する
     */
    fun Route.openApiRouter() {
        val generator: OpenApiGenerator = get(OpenApiGenerator::class.java)

        // OpenAPI JSON エンドポイント
        get("/openapi") {
            val document = generator.generate()
            call.respond(document)
        }

        // Scalar UI エンドポイント
        get("/scalar") {
            // リクエストパスからOpenAPIのURLを動的に計算
            // /scalar -> /openapi, /api/scalar -> /api/openapi
            val requestPath = call.request.path()
            val openApiUrl = requestPath.replaceLast("/scalar", "/openapi")

            call.respondText(
                contentType = ContentType.Text.Html,
                text = generateScalarHtml(openApiUrl)
            )
        }
    }

    /**
     * 文字列の最後に出現するパターンを置換する
     */
    private fun String.replaceLast(oldValue: String, newValue: String): String {
        val index = this.lastIndexOf(oldValue)
        return if (index < 0) this else this.substring(0, index) + newValue + this.substring(index + oldValue.length)
    }

    /**
     * Scalar UI用のHTMLを生成する
     * CDN経由でScalar API Referenceを読み込む
     *
     * @param openApiUrl OpenAPI specのURL（動的に計算される）
     */
    private fun generateScalarHtml(openApiUrl: String): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <title>MineAuth API Documentation</title>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1" />
                <style>
                    body {
                        margin: 0;
                        padding: 0;
                    }
                </style>
            </head>
            <body>
                <script
                    id="api-reference"
                    data-url="$openApiUrl"
                    data-configuration='{"theme": "purple"}'
                ></script>
                <script src="https://cdn.jsdelivr.net/npm/@scalar/api-reference"></script>
            </body>
            </html>
        """.trimIndent()
    }
}
