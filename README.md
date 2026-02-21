# MineAuth

OAuth2 / OpenID Connect authentication plugin for Minecraft servers.

## 📌 Overview

MineAuth is a Minecraft plugin that provides OAuth2 / OpenID Connect (OIDC) authentication.
It also enables other plugins to safely expose their data through an HTTP API.

## ✨ Features

- **OAuth2 / OIDC** - Standards-based authentication
- **Plugin API** - Let other plugins register HTTP endpoints
- **Scalar UI** - Interactive API docs at `/scalar`
- **OpenTelemetry** - Observability and tracing support

## 🚀 Quick Start

1. Put `MineAuth-<version>.jar` into your server's `plugins/`
2. Start the server (MineAuth generates config files)
3. Configure OAuth2 / OIDC settings
4. Open `http://localhost:8080/scalar` (if you kept the default port)

## 📚 Documentation

Docs: https://mineauth.plugin.morino.party

## 📄 License

- Core / API: [CC0-1.0](https://creativecommons.org/publicdomain/zero/1.0/)
- Addons: [GPL-3.0](https://www.gnu.org/licenses/gpl-3.0.html)
