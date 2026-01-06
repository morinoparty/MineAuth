# 📁 File-Based Record Data Reference

## 📂 File Structure

```
└── MineAuth/
    ├── certificate.pem
    ├── jwks.json
    ├── MineAuth.db
    ├── privateKey.pem
    ├── publicKey.pem
    │
    ├─ assets/
    │   └── lock.svg
    ├─ config/
    │   └── config.yml
    └─ templates/
        └── authorize.vm
```

---

## 📋 File Descriptions

### 🔐 Security Files

<table>
    <thead>
        <tr>
            <th>File Name</th>
            <th>Format</th>
            <th>Description</th>
        </tr>
    </thead>
    <tbody>
        <tr>
            <td>certificate.pem</td>
            <td>PEM</td>
            <td>X.509証明書（JWT署名検証用）</td>
        </tr>
        <tr>
            <td>privateKey.pem</td>
            <td>PEM</td>
            <td>RSA秘密鍵（JWT署名用）</td>
        </tr>
        <tr>
            <td>publicKey.pem</td>
            <td>PEM</td>
            <td>RSA公開鍵（JWT検証用）</td>
        </tr>
        <tr>
            <td>jwks.json</td>
            <td>JSON</td>
            <td>JSON Web Key Set（OIDC Discovery用）</td>
        </tr>
    </tbody>
</table>

---

### 💾 Database

<table>
    <thead>
        <tr>
            <th>File Name</th>
            <th>Format</th>
            <th>Description</th>
        </tr>
    </thead>
    <tbody>
        <tr>
            <td>MineAuth.db</td>
            <td>SQLite</td>
            <td>メインデータベース（ユーザー認証、OAuthクライアント等）</td>
        </tr>
    </tbody>
</table>

---

### ⚙️ Configuration

<table>
    <thead>
        <tr>
            <th>File Path</th>
            <th>Format</th>
            <th>Description</th>
        </tr>
    </thead>
    <tbody>
        <tr>
            <td>config/config.yml</td>
            <td>YAML</td>
            <td>統合設定ファイル（JWT, OAuth, WebServer設定）</td>
        </tr>
    </tbody>
</table>

---

### 📦 Resources

<table>
    <thead>
        <tr>
            <th>Directory</th>
            <th>Description</th>
        </tr>
    </thead>
    <tbody>
        <tr>
            <td>assets/</td>
            <td>静的アセットファイル（resources/assetsからコピー）</td>
        </tr>
        <tr>
            <td>templates/</td>
            <td>Velocityテンプレート（resources/templatesからコピー）</td>
        </tr>
    </tbody>
</table>
