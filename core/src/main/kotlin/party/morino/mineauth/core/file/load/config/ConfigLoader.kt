package party.morino.mineauth.core.file.load.config

import arrow.core.getOrElse
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import party.morino.mineauth.core.utils.json
import party.morino.mineauth.core.file.data.JWTConfigData
import party.morino.mineauth.core.file.data.MineAuthConfig
import party.morino.mineauth.core.file.data.OAuthConfigData
import party.morino.mineauth.core.file.data.ServerConfig
import party.morino.mineauth.core.file.data.WebServerConfigData
import party.morino.mineauth.core.file.data.isInsecureBaseUrl
import party.morino.mineauth.core.file.data.validateBaseUrl
import party.morino.mineauth.core.file.utils.JwtProvider
import party.morino.mineauth.core.file.utils.TokenVerifiers
import party.morino.mineauth.core.utils.PlayerPasswordHasher
import party.morino.mineauth.core.web.ratelimit.CidrRange
import party.morino.mineauth.core.web.ratelimit.OAuthRateLimits
import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * 統合設定ファイルローダー
 * Issue #66: 複数の設定ファイルを1つにまとめる
 *
 * plugins/MineAuth/config.json を読み込み、
 * 後方互換性のために各データクラスにも変換してKoinに登録する
 */
class ConfigLoader : AbstractConfigLoader() {
    override val configFile: File = plugin.dataFolder.resolve("config.json")

    override fun load() {
        // 設定ファイルが存在しない場合はデフォルト値で作成
        if (!configFile.exists()) {
            plugin.logger.info("config.json not found. Creating new one.")
            configFile.parentFile.mkdirs()
            configFile.createNewFile()
            val defaultConfig = MineAuthConfig()
            configFile.writeText(json.encodeToString(defaultConfig))
        }

        val decodedConfig: MineAuthConfig = decodeConfig(configFile.readText())
        // Koinへ登録する前に検証し、不正なissuerを公開しない
        // ここで失敗するとKoinには以前の設定が残り、/mineauth reload はその設定でHTTPサーバーを再開する
        checkBaseUrl(decodedConfig.server)

        val config: MineAuthConfig = ensurePepper(decodedConfig)
        // 初回のリクエストまで遅らせず、読み込みのたびにtrustedProxiesの誤りを警告するためここで作る
        val rateLimits = OAuthRateLimits(
            config.rateLimit,
            parseTrustedProxies(config.rateLimit.trustedProxies),
            plugin.logger
        )

        // 統合設定とパスワードハッシュ化クラスをKoinに登録
        // 流量制限も新しいインスタンスに置き換わるので、reloadで新しい上限が適用されカウンターはリセットされる
        loadKoinModules(module {
            single { config }
            single { PlayerPasswordHasher(config.password) }
            single { rateLimits }
        })

        // 後方互換性: 既存のデータクラスにも変換して登録
        loadLegacyModules(config)
    }

    /**
     * config.jsonの内容を解釈する
     *
     * パーサーのメッセージは2行目以降にエラー位置の前後の入力を引用し、そこにはデータベースのパスワードやpepperが含まれうる。
     * 起動時の有効化の失敗ログにも /mineauth reload の報告にも残らないよう、ここで引用を取り除いた例外に置き換える
     *
     * @param text config.jsonの内容
     * @return 解釈した設定
     * @throws ConfigParseException 解釈できなかった場合
     */
    private fun decodeConfig(text: String): MineAuthConfig = try {
        json.decodeFromString(text)
    } catch (e: SerializationException) {
        // 元の例外を原因として添付するとCaused byに引用がそのまま出るため、1行目だけを持つ例外を新しく送出する
        throw ConfigParseException.of(configFile.name, e)
    }

    /**
     * server.baseUrlを検証する
     * クエリやフラグメントは仕様違反のissuerになるため読み込みを失敗させ、httpsでない場合は開発環境もあるので警告に留める
     *
     * @param server 読み込んだサーバー設定
     * @throws IllegalStateException baseUrlが不正な場合
     */
    private fun checkBaseUrl(server: ServerConfig) {
        val baseUrl = server.validateBaseUrl().getOrElse { error(it.message) }
        if (isInsecureBaseUrl(baseUrl)) {
            plugin.logger.warning(
                "server.baseUrl (${server.baseUrl}) does not use https. " +
                    "OpenID Connect requires https for the issuer and jwks_uri, so clients may reject this server."
            )
        }
    }

    /**
     * rateLimit.trustedProxiesを解析する
     * 解釈できない値を無視しても信頼する範囲が狭まるだけで穴は開かないので、起動は止めずに警告に留める
     *
     * @param entries 設定されたIPアドレスまたはCIDR
     * @return 解釈できた範囲
     */
    private fun parseTrustedProxies(entries: List<String>): List<CidrRange> = entries.mapNotNull { entry ->
        CidrRange.parse(entry).also { range ->
            if (range == null) {
                plugin.logger.warning(
                    "rateLimit.trustedProxies entry ($entry) is ignored because it is not an IP address or CIDR range."
                )
            }
        }
    }

    /**
     * pepperが未設定の場合にランダム生成してconfig.jsonへ保存する
     * 起動ごとに値が変わるとパスワードを検証できなくなるため、必ずファイルに永続化する
     *
     * @param config 読み込んだ設定
     * @return pepperが設定された設定
     */
    private fun ensurePepper(config: MineAuthConfig): MineAuthConfig {
        if (config.password.pepper != null) return config

        plugin.logger.info("Password pepper is not set. Generating a new one.")
        val updated = config.copy(password = config.password.copy(pepper = generatePepper()))
        // 書き戻しで未知のキーや書式が失われるため、元のファイルを退避しておく
        configFile.copyTo(configFile.resolveSibling("${configFile.name}.bak"), overwrite = true)
        configFile.writeText(json.encodeToString(updated))
        return updated
    }

    /**
     * 暗号論的乱数で32バイトのpepperを生成する
     *
     * @return Base64URL形式のpepper
     */
    private fun generatePepper(): String {
        val bytes = ByteArray(PEPPER_BYTES).also { SecureRandom().nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * 後方互換性のために既存のデータクラスに変換してKoinに登録
     * 既存コードが JWTConfigData, OAuthConfigData, WebServerConfigData を
     * 直接injectしている場合でも動作するようにする
     */
    private fun loadLegacyModules(config: MineAuthConfig) {
        // issuerはDiscoveryと同じくbaseUrlから作る（OIDC Discovery 1.0 Section 4.3: issuerとissは一致必須）
        val issuer = JWTConfigData.normalizeIssuer(config.server.baseUrl)
        warnIfLegacyIssuerDiffers(config.jwt.issuer, issuer)

        // JWTConfigData への変換
        val jwtConfigData = JWTConfigData(
            issuer = issuer,
            realm = config.jwt.realm,
            privateKeyFile = config.jwt.privateKeyFile,
            keyId = config.jwt.keyId,
            trustedIssuers = config.jwt.trustedIssuers
        )

        // OAuthConfigData への変換
        val oauthConfigData = OAuthConfigData(
            applicationName = config.oauth.applicationName,
            logoUrl = config.oauth.logoUrl
        )

        // WebServerConfigData への変換
        val webServerConfigData = WebServerConfigData(
            port = config.server.port,
            ssl = config.server.ssl
        )

        loadKoinModules(module {
            single { jwtConfigData }
            single { oauthConfigData }
            single { webServerConfigData }
            // 種別ごとのJWT Verifier
            // 読み込みのたびに置き換え、/mineauth reload 後は新しいissuerとtrustedIssuersだけを受け付ける
            // 署名鍵はKeyUtils.initより後でないと読めないため、初回参照時に構築する
            single { TokenVerifiers(JwtProvider.algorithm, jwtConfigData.acceptedIssuers) }
        })
    }

    /**
     * 非推奨のjwt.issuerがbaseUrlと異なる場合に警告する
     * 以前はjwt.issuerがトークンのissに使われていたため、値が残っていると設定者が混乱しやすい
     *
     * @param legacyIssuer config.jsonのjwt.issuer（未設定ならnull）
     * @param issuer 実際に使うissuer
     */
    private fun warnIfLegacyIssuerDiffers(legacyIssuer: String?, issuer: String) {
        if (legacyIssuer == null || JWTConfigData.normalizeIssuer(legacyIssuer) == issuer) return
        plugin.logger.warning(
            "jwt.issuer ($legacyIssuer) is deprecated and ignored. " +
                "Tokens are issued with iss=$issuer (server.baseUrl). " +
                "Add other servers' issuers to jwt.trustedIssuers to accept their tokens."
        )
    }

    private companion object {
        // pepperの長さ（バイト）
        const val PEPPER_BYTES = 32
    }
}
