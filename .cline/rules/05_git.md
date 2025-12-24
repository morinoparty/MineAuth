# Gitのルール

- ブランチを切る際は、masterブランチから切ること
- プルリクエストは必ずmasterブランチに対して行うこと
- ブランチを切ってから、作業を始める前に、masterブランチの最新の状態を取り込むこと
- ブランチを切って作業をすること

## Repository
- [MoripaFishing](https://github.com/morinoparty/MoripaFishing)

## コミットメッセージ
- コミットメッセージは英語で書き、以下のような形式で書く。

```
emoji コミットの概要

```

例: 
```
🎨 Add new method to get fish
```
commitの絵文字などに関しては、changelog.config.jsを参考にしてください

## Issueについて
- 新しい機能を追加する場合は、Issueを作成してください。
- Issueは英語で書き、適切なラベルを追加してください。
- 現状存在しないラベルについては、勝手に作成しないでください
- どうしても必要である場合は、.github/labels.jsonに追加してください