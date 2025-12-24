---
sidebar_position: 2
---

# 🔗 API Endpoints
This is an endpoint list for OAuth2.0 and OpenID Connect (OIDC) for Moripa API.

## 🌐 Endpoints

### 🔒 OAuth2.0

<table>
  <thead>
    <tr>
      <th>Implemented</th>
      <th>Endpoint</th>
      <th>Path</th>
      <th>Specification</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td>✅</td>
      <td>Authorization</td>
      <td><code>/oauth2/authorize</code></td>
      <td><a href="https://datatracker.ietf.org/doc/html/rfc6749.html#section-4.1.1">RFC 6749 4.1.1</a></td>
    </tr>
    <tr>
      <td>✅</td>
      <td>Token</td>
      <td><code>/oauth2/token</code></td>
      <td><a href="https://datatracker.ietf.org/doc/html/rfc6749.html#section-4.1.3">RFC 6749 4.1.3</a></td>
    </tr>
    <tr>
      <td>❌</td>
      <td>Introspection</td>
      <td><code>/oauth2/introspect</code></td>
      <td><a href="https://datatracker.ietf.org/doc/html/rfc7662.html#section-2.1">RFC 7662</a></td>
    </tr>
    <tr>
      <td>❌</td>
      <td>Revocation</td>
      <td><code>/oauth2/revoke</code></td>
      <td><a href="https://datatracker.ietf.org/doc/html/rfc7009.html#section-2.1">RFC 7009</a></td>
    </tr>
  </tbody>
</table>

### 🔍 OpenID Connect (OIDC)

<table>
  <thead>
    <tr>
      <th>Implemented</th>
      <th>Endpoint</th>
      <th>Path</th>
      <th>Specification</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td>✅</td>
      <td>Discovery</td>
      <td><code>/.well-known/openid-configuration</code></td>
      <td><a href="https://openid.net/specs/openid-connect-discovery-1_0.html">OIDC Discovery 1.0</a></td>
    </tr>
    <tr>
      <td>✅</td>
      <td>JWKs</td>
      <td><code>/.well-known/jwks.json</code></td>
      <td>-</td>
    </tr>
    <tr>
      <td>✅</td>
      <td>UserInfo</td>
      <td><code>/oauth2/userinfo</code></td>
      <td><a href="https://openid-foundation-japan.github.io/openid-connect-core-1_0.ja.html#UserInfo">OIDC Core 5.3</a></td>
    </tr>
  </tbody>
</table>

### 🔌 Plugin

<table>
  <thead>
    <tr>
      <th>Endpoint</th>
      <th>Path</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td>Plugin</td>
      <td><code>/api/v1/plugin/&lt;pluginName&gt;/*</code></td>
    </tr>
  </tbody>
</table>
