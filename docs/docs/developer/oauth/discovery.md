---
sidebar_position: 3
---

# 🔍 OIDC Discovery

OIDC Discovery エンドポイントは、OpenID Connect Discovery 1.0 に準拠したメタデータを返します。

## エンドポイント

```
GET /.well-known/openid-configuration
```

## レスポンス例

```json
{
  "issuer": "https://api.example.com",
  "authorization_endpoint": "https://api.example.com/oauth2/authorize",
  "token_endpoint": "https://api.example.com/oauth2/token",
  "userinfo_endpoint": "https://api.example.com/oauth2/userinfo",
  "jwks_uri": "https://api.example.com/.well-known/jwks.json",
  "response_types_supported": ["code"],
  "subject_types_supported": ["public"],
  "id_token_signing_alg_values_supported": ["RS256"],
  "scopes_supported": ["openid", "profile"],
  "token_endpoint_auth_methods_supported": ["client_secret_post", "none"],
  "claims_supported": [
    "sub",
    "name",
    "nickname",
    "picture",
    "iss",
    "aud",
    "exp",
    "iat",
    "auth_time",
    "nonce",
    "at_hash"
  ],
  "grant_types_supported": ["authorization_code", "refresh_token"],
  "code_challenge_methods_supported": ["S256"]
}
```

## メタデータ項目

| 項目 | 説明 |
|------|------|
| `issuer` | Issuer Identifier（config.jsonのbaseUrl） |
| `authorization_endpoint` | 認可エンドポイント |
| `token_endpoint` | トークンエンドポイント |
| `userinfo_endpoint` | UserInfoエンドポイント |
| `jwks_uri` | JWKsエンドポイント |
| `response_types_supported` | サポートするレスポンスタイプ |
| `subject_types_supported` | サポートするサブジェクトタイプ |
| `id_token_signing_alg_values_supported` | IDトークンの署名アルゴリズム |
| `scopes_supported` | サポートするスコープ |
| `token_endpoint_auth_methods_supported` | トークンエンドポイントの認証方法 |
| `claims_supported` | サポートするクレーム |
| `grant_types_supported` | サポートするグラントタイプ |
| `code_challenge_methods_supported` | サポートするPKCEメソッド |

## 参考

- [OpenID Connect Discovery 1.0](https://openid.net/specs/openid-connect-discovery-1_0.html)
