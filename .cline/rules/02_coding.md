# コーディングプラクティス

## 実装手順

1. **型設計**
   - まず型(interface)を定義

2. **純粋関数から実装**
   - 外部依存のない関数を先に実装

## プラクティス

- 小さく始めて段階的に拡張
- 過度な抽象化を避ける
- コードよりも型を重視
- 複雑さに応じてアプローチを調整



## コードスタイル

- 常に既存コードの設計や記法を参考にしてください。
- Koinを利用したDIを積極的に利用してください。
- GoFのデザインパターンを意識して設計してください。
- ArrowKtのEitherやOptionなどのモナドを積極的に利用してください。
- 書籍「リーダブルコード」のようなベストプラクティスを常に適用してください。
- コードの意図・背景などのコメントを各行に日本語で積極的に入れてください。また関数にはKDocを入れることが推奨されます。
- クラスごとにファイルを分けてください。
- 適切にpackageを作成してください。
- コードを書いた後は、`task build`を実行して、コードが正しくビルドされることを確認してください。`

## 補足

エディターの仕様上`Unresolved reference: UUIDkotlin(UNRESOLVED_REFERENCE)`などが出ることがあるが無視してください。