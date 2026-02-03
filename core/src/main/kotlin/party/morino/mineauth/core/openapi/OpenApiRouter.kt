package party.morino.mineauth.core.openapi

import io.ktor.http.*
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
            call.respondText(
                contentType = ContentType.Text.Html,
                text = generateScalarHtml()
            )
        }
    }

    /**
     * Scalar UI用のHTMLを生成する
     * CDN経由でScalar API Referenceを読み込む
     */
    private fun generateScalarHtml(): String {
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
                    data-url="/openapi"
                    data-configuration='{"theme": "purple"}'
                ></script>
                <script src="https://cdn.jsdelivr.net/npm/@scalar/api-reference"></script>
            </body>
            </html>
        """.trimIndent()
    }
}
