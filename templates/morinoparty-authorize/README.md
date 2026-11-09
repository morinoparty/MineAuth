# Morino Party Authorization Page

The authorization page used by Morino Party. It is a custom page for MineAuth, built with React and `@morinoparty/chlorophyll-react`. It is independent of the bundled page in `core/src/main/frontend/`, and the Gradle build does not use it.

## Building

```bash
pnpm install
pnpm run build
```

The build outputs a single self-contained `dist/index.html`. `pnpm dev` previews the page with sample data.

## Deploying

```
plugins/MineAuth/
├── web/
│   └── authorize.html        # dist/index.html, renamed
└── assets/
    └── cover/
        ├── covers.json
        └── *.avif
```

MineAuth reloads `web/authorize.html` on the next request after the file changes, so a restart is not required.

### Automatic Deployment

The `deploy authorize page` workflow builds the page and uploads `dist/index.html` to each server through the Pelican Panel client API. It runs on pushes to `master` that change this directory, and can also be run manually. Cover images are not deployed by the workflow.

Configure the following repository secrets:

| Name | Description |
|------|-------------|
| `PELICAN_PANEL_URL` | URL of the panel, such as `https://panel.example.com`. |
| `PELICAN_SERVER_IDS` | Comma-separated server identifiers, such as `1a2b3c4d,5e6f7a8b`. |
| `PELICAN_CLIENT_API_KEY` | Client API key of a user that can write files on every listed server. |

To set them from a file, copy `.env.example` to `.env`, fill in the values, and run the following command. `.env` is ignored by Git.

```bash
gh secret set -f .env
```

Use a dedicated subuser that has only the file permissions, because a client API key can access every server that its user can access. If the key has an IP allowlist, GitHub-hosted runners cannot use it.

## Cover Images

The page reads `assets/cover/covers.json` and displays one image at random on screens at least 1024px wide. When the file or the image cannot be loaded, the page displays a gradient instead.

```json
{
  "images": [
    {
      "src": "morimoto.avif",
      "blurhash": "LS98rlMwaJnhu6VroyjFI9s.o#f7",
      "width": 1920,
      "height": 1080,
      "position": "5% center"
    }
  ]
}
```

| Field | Required | Description |
|-------|----------|-------------|
| `src` | Yes | Path relative to `assets/cover/`, or an absolute URL. |
| `blurhash` | Yes | Placeholder displayed until the image is loaded. |
| `width`, `height` | Yes | Size of the image in pixels. |
| `position` | No | CSS `object-position`. The default is `center`. |

If any entry is invalid, the whole file is ignored.
