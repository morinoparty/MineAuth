package party.morino.mineauth.core.file.utils

import arrow.core.getOrElse
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.util.io.pem.PemObject
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.file.data.JWTConfigData
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.*

/**
 * RSA鍵ペアの管理ユーティリティ
 * 鍵の生成と読み込みを行い、証明書とJWKsは SigningKeyPublisher で署名鍵に揃える
 *
 * 生成されるファイルは plugins/MineAuth/generated/ に配置される
 */
object KeyUtils : KoinComponent {
    private val plugin: MineAuth by inject()

    // 生成ファイル用ディレクトリ
    val generatedDir: File
        get() = plugin.dataFolder.resolve("generated")

    /**
     * 鍵ペアを用意し、公開用ファイルを署名鍵とkidに揃える
     * 起動時と /mineauth reload の両方で呼ばれる
     */
    fun init() {
        // generated ディレクトリを作成
        generatedDir.mkdirs()

        generateKeyPair()

        // getKeys()は実際に署名に使っている鍵を返すので、reload時も署名と同じ鍵で比較できる
        val (privateKey, publicKey) = getKeys()
        warnIfKeyFilesChanged()

        // JWTConfigData は ConfigLoader で既に登録済みで、reloadでは新しいkeyIdに置き換わっている
        // トークンのkidも発行時に同じ値を取得するため、ここで揃えておけば一致する
        val jwtConfigData: JWTConfigData = get()
        SigningKeyPublisher(generatedDir, plugin.logger)
            .publish(privateKey as RSAPrivateKey, publicKey as RSAPublicKey, jwtConfigData.keyId)
            .getOrElse { error(it.message) }
    }

    /**
     * ディスク上の鍵が署名に使っている鍵と異なる場合に警告する
     *
     * 署名鍵は起動時に一度だけ読み込むため、reloadでは差し替わらない。
     * 差し替えたつもりで古い鍵のまま動き続けることのないよう、再起動が必要なことを管理者へ知らせる。
     * 読み込めないファイルも再起動時に問題になるので同様に警告する（署名には影響しないのでreloadは失敗させない）。
     */
    private fun warnIfKeyFilesChanged() {
        val keysOnDisk = try {
            loadKeysFromDisk()
        } catch (e: IOException) {
            null
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            // Base64として不正な場合
            null
        }
        if (keysOnDisk != getKeys()) {
            plugin.logger.warning(
                "privateKey.pem or publicKey.pem on disk differs from the signing key in use. " +
                    "Key files are read once at startup, so restart the server to apply them."
            )
        }
    }

    private fun generateKeyPair() {
        val privateKeyFile = generatedDir.resolve("privateKey.pem")
        val publicKeyFile = generatedDir.resolve("publicKey.pem")
        if (privateKeyFile.exists() || publicKeyFile.exists()) {
            plugin.logger.warning("Key files already exist.")
            return
        }
        privateKeyFile.parentFile.mkdirs()
        publicKeyFile.parentFile.mkdirs()
        privateKeyFile.createNewFile()
        publicKeyFile.createNewFile()
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048, SecureRandom())
        val keyPair = keyPairGenerator.generateKeyPair()

        JcaPEMWriter(privateKeyFile.writer()).use { pemWriter ->
            pemWriter.writeObject(
                PemObject("PRIVATE KEY", keyPair.private.encoded)
            )
        }

        JcaPEMWriter(publicKeyFile.writer()).use { pemWriter ->
            pemWriter.writeObject(
                PemObject("PUBLIC KEY", keyPair.public.encoded)
            )
        }
    }

    // 鍵ペアのキャッシュ（PEMファイルの読み込みは初回のみ）
    // lazy はデフォルトでスレッドセーフ（SynchronizedLazyImpl）
    private val cachedKeys: Pair<PrivateKey, PublicKey> by lazy { loadKeysFromDisk() }

    /**
     * RSA鍵ペアを取得する（キャッシュ済み）
     * 初回呼び出し時にPEMファイルから読み込み、以降はキャッシュを返す
     *
     * @return Pair(秘密鍵, 公開鍵)
     */
    fun getKeys(): Pair<PrivateKey, PublicKey> = cachedKeys

    /**
     * PEMファイルからRSA鍵ペアを読み込む（内部用）
     */
    private fun loadKeysFromDisk(): Pair<PrivateKey, PublicKey> {
        val privateKeyFile = generatedDir.resolve("privateKey.pem")
        val publicKeyFile = generatedDir.resolve("publicKey.pem")
        val privateKeyContent = privateKeyFile.readText()
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s+".toRegex(), "")
        val publicKeyContent = publicKeyFile.readText()
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s+".toRegex(), "")
        val keyFactory = KeyFactory.getInstance("RSA")
        val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyContent)))
        val publicKey = keyFactory.generatePublic(
            X509EncodedKeySpec(
                Base64.getDecoder().decode(publicKeyContent)
            )
        )
        return Pair(privateKey, publicKey)
    }
}
