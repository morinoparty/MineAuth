# ⚙️ Configuration

MineAuthでは、プラグインの設定を`plugins/MineAuth/config.json`ファイルで管理します。

## 📁 ファイル構成

設定ファイルは`plugins/MineAuth/`ディレクトリに配置されます。

```
plugins/MineAuth/
├── config.json          # メイン設定ファイル
├── clients.json         # OAuthクライアント設定
└── generated/           # 自動生成ファイル
    ├── privateKey.pem   # RSA秘密鍵
    ├── publicKey.pem    # RSA公開鍵
    ├── certificate.pem  # X.509証明書
    └── jwks.json        # JWK Set
```

## ⚙️ config.json

```json5
{
  "server": {
    "baseUrl": "https://api.example.com",  // OIDCのissuerおよびエンドポイントのベースURL
    "port": 8080,
    "ssl": {
      "sslPort": 8443,
      "keyStore": "keystore.jks",
      "keyAlias": "MineAuth",
      "keyStorePassword": "password",
      "privateKeyPassword": "password"
    } // SSLが不要な場合はnullまたは省略可能
  },
  "jwt": {
    "issuer": "https://api.example.com/",  // JWTトークンの発行者
    "realm": "example.com",
    "privateKeyFile": "privateKey.pem",
    "keyId": "a22c063a-a708-c801-6f92-49f6d53b89b2"  // JWKのキーID
  },
  "oauth": {
    "applicationName": "MineAuth",  // 認可画面に表示されるアプリケーション名
    "logoUrl": "/assets/lock.svg"   // 認可画面に表示されるロゴURL
  }
}
```

### 📝 設定項目の説明

#### server

| キー | 型 | 必須 | 説明 |
|------|------|------|------|
| `baseUrl` | string | ✅ | OIDC Discoveryで公開されるエンドポイントのベースURL。リバースプロキシを使用する場合はプロキシのURLを設定してください |
| `port` | number | ✅ | HTTPサーバーのポート番号 |
| `ssl` | object | ❌ | SSL設定（省略可能） |

#### jwt

| キー | 型 | 必須 | 説明 |
|------|------|------|------|
| `issuer` | string | ✅ | JWTトークンの発行者識別子 |
| `realm` | string | ✅ | JWT認証のレルム名 |
| `privateKeyFile` | string | ✅ | 秘密鍵ファイルのパス（generated/からの相対パス） |
| `keyId` | string | ✅ | JWKのキーID（UUID形式） |

#### oauth

| キー | 型 | 必須 | 説明 |
|------|------|------|------|
| `applicationName` | string | ✅ | OAuth認可画面に表示されるアプリケーション名 |
| `logoUrl` | string | ✅ | OAuth認可画面に表示されるロゴのURL |

## 🔐 自動生成ファイル

`generated/`ディレクトリには、プラグインが自動的に生成する暗号鍵と証明書が配置されます。

- **privateKey.pem**: RS256署名に使用するRSA秘密鍵
- **publicKey.pem**: RSA公開鍵
- **certificate.pem**: 自己署名X.509証明書
- **jwks.json**: JWK Set（公開鍵をJSON Web Key形式で公開）

これらのファイルは初回起動時に自動生成されます。削除した場合は再生成されますが、既存のトークンは無効になります。
