package party.morino.mineauth.core.web.router.auth.oauth

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import com.auth0.jwt.interfaces.Payload
import kotlinx.coroutines.runBlocking
import party.morino.mineauth.core.model.TokenKind
import party.morino.mineauth.core.model.grantId
import party.morino.mineauth.core.repository.AccountRepository
import party.morino.mineauth.core.repository.OAuthClientRepository
import party.morino.mineauth.core.repository.RevokedGrantRepository
import party.morino.mineauth.core.repository.RevokedTokenRepository
import party.morino.mineauth.core.repository.ServiceAccountTokenRepository

/**
 * 署名検証済みトークンの状態を種別ごとの規則で検証する
 *
 * Bearer認証とイントロスペクションの両方がこのクラスを使うことで、
 * 一方だけ検証項目が増えて判定が食い違う事態を防ぐ（RFC 7662 Section 4）
 *
 * 判定専用であり、最終使用日時の更新のような副作用は持たない。
 * イントロスペクションの問い合わせを「使用」として記録しないための分離である
 */
class TokenStateValidator {

    /**
     * トークンの状態を検証する
     *
     * @param kind 適用する規則の種別
     * @param payload 署名検証済みのペイロード
     * @return 全ての検証に通った場合はRight、失敗した場合は最初に検出した理由
     */
    suspend fun validate(kind: TokenKind, payload: Payload): Either<TokenStateFailure, Unit> = either {
        when (kind) {
            TokenKind.ACCESS, TokenKind.REFRESH -> {
                ensureClaimMatches(kind, payload)
                ensureClientExists(payload)
                ensureNotRevoked(payload)
            }
            TokenKind.SERVICE -> {
                ensureClaimMatches(kind, payload)
                ensureAccountExists(payload)
                ensureServiceTokenValid(payload)
            }
            // ID Tokenと不明な種別はアクセス権を表さないため、状態によらず有効として扱わない
            TokenKind.ID, TokenKind.UNKNOWN -> raise(TokenStateFailure.WRONG_TOKEN_TYPE)
        }
    }

    /**
     * トークンの状態を検証する（ブロッキング版）
     * JWT検証コールバック用のヘルパーメソッド
     *
     * @param kind 適用する規則の種別
     * @param payload 署名検証済みのペイロード
     * @return 全ての検証に通った場合はRight、失敗した場合は最初に検出した理由
     */
    fun validateBlocking(kind: TokenKind, payload: Payload): Either<TokenStateFailure, Unit> = runBlocking {
        validate(kind, payload)
    }

    /**
     * token_typeクレームが規則の種別と一致することを確認する
     * Bearer認証のvalidateブロックにはJOSEヘッダーが渡されないため、プロバイダ間の種別の絞り込みはこのクレームで行う
     * （typとクレームの整合はVerifierが保証している）
     */
    private fun Raise<TokenStateFailure>.ensureClaimMatches(kind: TokenKind, payload: Payload) {
        val tokenType = payload.getClaim(TokenKind.TOKEN_TYPE_CLAIM).asString()
        ensure(tokenType == kind.tokenTypeClaim) { TokenStateFailure.WRONG_TOKEN_TYPE }
    }

    /**
     * トークンを発行したOAuthクライアントが存在することを確認する
     * 削除されたクライアントのトークンは、有効期限内でも有効として扱わない
     */
    private suspend fun Raise<TokenStateFailure>.ensureClientExists(payload: Payload) {
        val clientId = ensureNotNull(payload.getClaim("client_id").asString()) {
            TokenStateFailure.CLIENT_NOT_FOUND
        }
        ensure(OAuthClientRepository.findById(clientId).isRight()) { TokenStateFailure.CLIENT_NOT_FOUND }
    }

    /**
     * アクセストークン・リフレッシュトークンが失効していないことを確認する
     *
     * 発行する全トークンがjtiを持つため、jtiがなければ失効状態を確認できないものとして拒否する。
     * grant_idの導入前に発行された旧形式のトークンはグラントを持たないので、jti単位の確認だけを行う
     */
    private suspend fun Raise<TokenStateFailure>.ensureNotRevoked(payload: Payload) {
        val tokenId = ensureNotNull(payload.id) { TokenStateFailure.REVOKED }
        ensure(!RevokedTokenRepository.isRevoked(tokenId)) { TokenStateFailure.REVOKED }
        payload.grantId()?.let { grantId ->
            ensure(!RevokedGrantRepository.isRevoked(grantId)) { TokenStateFailure.GRANT_REVOKED }
        }
    }

    /**
     * サービスアカウントが存在することを確認する
     */
    private suspend fun Raise<TokenStateFailure>.ensureAccountExists(payload: Payload) {
        val accountId = ensureNotNull(payload.getClaim("account_id").asString()) {
            TokenStateFailure.ACCOUNT_ID_MISSING
        }
        ensure(AccountRepository.findById(accountId).isRight()) { TokenStateFailure.ACCOUNT_NOT_FOUND }
    }

    /**
     * サービストークンが失効も再発行もされていないことを確認する
     * サービストークンの失効状態はrevoked_tokensではなくサービスアカウントトークンのテーブルが持つ
     */
    private suspend fun Raise<TokenStateFailure>.ensureServiceTokenValid(payload: Payload) {
        val tokenId = ensureNotNull(payload.id) { TokenStateFailure.REVOKED }
        ensure(ServiceAccountTokenRepository.isTokenValid(tokenId)) { TokenStateFailure.REVOKED }
    }
}
