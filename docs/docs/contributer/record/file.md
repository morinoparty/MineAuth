# 📁 File-Based Record Data Reference

## 📂 File Structure

```
└── MineAuth/
    ├── config.json
    ├── MineAuth.db
    │
    ├─ generated/          # 自動生成（編集不要）
    │   ├── certificate.pem
    │   ├── jwks.json
    │   ├── privateKey.pem
    │   └── publicKey.pem
    │
    ├─ assets/
    │   └── lock.svg
    │
    └─ templates/
        └── authorize.vm
```

---

## 📋 File Descriptions

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
            <td>config.json</td>
            <td>JSON</td>
            <td>統合設定ファイル（Server, JWT, OAuth設定）</td>
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

### 🔐 Generated Security Files

`generated/` ディレクトリ内のファイルは初回起動時に自動生成されます。手動で編集する必要はありません。

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
            <td>privateKey.pem</td>
            <td>PEM</td>
            <td>RSA秘密鍵（2048bit、JWT署名用）</td>
        </tr>
        <tr>
            <td>publicKey.pem</td>
            <td>PEM</td>
            <td>RSA公開鍵（JWT検証用）</td>
        </tr>
        <tr>
            <td>certificate.pem</td>
            <td>PEM</td>
            <td>自己署名X.509証明書（有効期限1年）</td>
        </tr>
        <tr>
            <td>jwks.json</td>
            <td>JSON</td>
            <td>JSON Web Key Set（OIDC Discovery用）</td>
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
