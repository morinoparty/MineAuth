# ⚙️ Configuration

MineAuth の設定ファイルは `plugins/MineAuth/config.json` に配置されます。

## 設定ファイル

```json5
{
  "server": {
    // 外部公開URL（OIDC Discoveryで使用）
    "baseUrl": "https://api.example.com",
    // HTTPサーバーポート
    "port": 8080,
    // SSL設定（不要な場合はnull）
    "ssl": {
      "sslPort": 8443,
      "keyStore": "keystore.jks",
      "keyAlias": "MineAuth",
      "keyStorePassword": "password",
      "privateKeyPassword": "password"
    }
  },
  "jwt": {
    // JWTのissuer（通常はbaseUrlと同じ）
    "issuer": "https://api.example.com",
    // JWTのrealm
    "realm": "example.com",
    // 秘密鍵ファイル名（generated/相対）
    "privateKeyFile": "privateKey.pem",
    // JWKのキーID
    "keyId": "a22c063-a708-c801-6f92-49f6d53b89b2"
  },
  "oauth": {
    // 認可画面に表示するアプリケーション名
    "applicationName": "MineAuth",
    // 認可画面に表示するロゴURL
    "logoUrl": "/assets/lock.svg"
  }
}
```

## ディレクトリ構造

```
plugins/MineAuth/
├── config.json          # 設定ファイル
├── generated/           # 自動生成ファイル（編集不要）
│   ├── privateKey.pem   # RSA秘密鍵
│   ├── publicKey.pem    # RSA公開鍵
│   ├── certificate.pem  # 証明書
│   └── jwks.json        # JWKs
├── assets/              # 静的ファイル
└── MineAuth.db          # SQLiteデータベース
```

## 設定項目

### server

| 項目 | 型 | 説明 |
|------|---|------|
| `baseUrl` | String | 外部公開URL。OIDC Discovery で使用される |
| `port` | Int | HTTPサーバーポート（デフォルト: 8080） |
| `ssl` | Object/null | SSL設定。不要な場合は `null` |

### jwt

| 項目 | 型 | 説明 |
|------|---|------|
| `issuer` | String | JWTのissuer。通常は `baseUrl` と同じ |
| `realm` | String | JWTのrealm |
| `privateKeyFile` | String | 秘密鍵ファイル名 |
| `keyId` | UUID | JWKのキーID（自動生成） |

### oauth

| 項目 | 型 | 説明 |
|------|---|------|
| `applicationName` | String | 認可画面に表示するアプリケーション名 |
| `logoUrl` | String | 認可画面に表示するロゴURL |
