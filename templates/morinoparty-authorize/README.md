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
