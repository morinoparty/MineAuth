# 💾 Database Record Data Reference

## 👤 UserAuthData

| UUID        | Password      | Temporary |
|-------------|---------------|-----------|
| Player UUID | argon2 hashed | Boolean   |

## 🔑 RevokeTokenData

| TokenId        | Exp data |
|----------------|----------|
| Token Id (200) | Date     |