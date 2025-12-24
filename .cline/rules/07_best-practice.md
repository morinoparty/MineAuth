# 推奨される書き方


## DI
- DIを使う際には、コンストラクタインジェクションではなく、Koinの`by inject()`を使うことが推奨されます。

## Test
- core/pluginにあるUseCaseに関してはテストを記述するようにしてください。
- テストの数については、あまり大量にせず、メソッドの主要な分岐点をカバーする程度に留めることが推奨されます。
- また、@DisplayNameアノテーションを使用して、テストケースの説明を明確にすることが推奨されます。これは、英語で短く記述してください。

## Minecraft
- player | senderにメッセージを送る際には、sender.sendMessageではなく、sender.sendRichMessage(minimessage : string)を使うことが推奨されます。