package party.morino.mineauth.core.dialog

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput
import io.papermc.paper.registry.data.dialog.input.TextDialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.event.ClickCallback
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.entity.Player

/**
 * OAuthクライアント作成ダイアログ
 * プレイヤーにフォームを表示し、入力を受け付ける
 */

object OAuthClientCreateDialog {
    // 入力フィールドのキー定数
    private const val KEY_CLIENT_NAME = "client_name"
    private const val KEY_CLIENT_TYPE = "client_type"
    private const val KEY_REDIRECT_URI = "redirect_uri"

    // 入力フィールドの幅
    private const val INPUT_WIDTH_NAME = 300
    private const val INPUT_WIDTH_TYPE = 300
    private const val INPUT_WIDTH_URI = 300

    // 入力フィールドの最大長
    private const val MAX_LENGTH_NAME = 64
    // 複数URIを改行区切りで入力するため、1件分より長くしておく
    private const val MAX_LENGTH_URI = 2048

    // リダイレクトURI入力欄の行数と高さ（複数URIを1行に1つずつ入力する）
    private const val MAX_LINES_URI = 8
    private const val HEIGHT_URI = 96

    /**
     * ダイアログを表示する
     * @param player 表示対象のプレイヤー
     */
    fun show(player: Player) {
        val dialog = Dialog.create { factory ->
            factory.empty()
                .base(
                    DialogBase.builder(Component.text("OAuthクライアント作成"))
                        .canCloseWithEscape(true)
                        .body(
                            listOf(
                                DialogBody.plainMessage(
                                        MiniMessage.miniMessage().deserialize("新しいOAuthクライアントを作成します。 <newline>必要な情報を入力してください。")
                                )
                            )
                        )
                        .inputs(buildInputs())
                        .build()
                )
                .type(
                    DialogType.confirmation(
                        // 作成ボタン
                        buildCreateButton(player),
                        // キャンセルボタン
                        buildCancelButton()
                    )
                )
        }

        player.showDialog(dialog)
    }

    /**
     * 入力フィールドを構築する
     */
    private fun buildInputs(): List<DialogInput> {
        return listOf(
            // クライアント名（テキスト入力）
            DialogInput.text(KEY_CLIENT_NAME, Component.text("クライアント名"))
                .width(INPUT_WIDTH_NAME)
                .initial("")
                .maxLength(MAX_LENGTH_NAME)
                .build(),

            // クライアント種別（単一選択）
            DialogInput.singleOption(
                KEY_CLIENT_TYPE,
                Component.text("クライアント種別"),
                listOf(
                    SingleOptionDialogInput.OptionEntry.create(
                        "public",
                        Component.text("Public（ブラウザ・モバイルアプリ向け）"),
                        true // 初期選択
                    ),
                    SingleOptionDialogInput.OptionEntry.create(
                        "confidential",
                        Component.text("Confidential（サーバーアプリ向け）"),
                        false
                    )
                )
            ).width(INPUT_WIDTH_TYPE).build(),

            // リダイレクトURI（テキスト入力）
            // 複数登録できるよう複数行入力にし、1行に1つのURIを書いてもらう
            DialogInput.text(KEY_REDIRECT_URI, Component.text("リダイレクトURI（1行に1つ）"))
                .width(INPUT_WIDTH_URI)
                .initial("http://localhost:8080/callback")
                .maxLength(MAX_LENGTH_URI)
                .multiline(TextDialogInput.MultilineOptions.create(MAX_LINES_URI, HEIGHT_URI))
                .build()
        )
    }

    /**
     * 作成ボタンを構築する
     */
    private fun buildCreateButton(player: Player): ActionButton {
        return ActionButton.builder(Component.text("作成"))
            .action(
                DialogAction.customClick(
                    { view, _ ->
                        // コールバック内で入力値を取得して処理
                        val clientName = view.getText(KEY_CLIENT_NAME)
                        val clientType = view.getText(KEY_CLIENT_TYPE)
                        val redirectUris = view.getText(KEY_REDIRECT_URI)

                        // 非同期で作成処理を実行
                        OAuthClientCreateHandler.handleCreate(
                            player,
                            clientName ?: "",
                            clientType ?: "public",
                            redirectUris ?: ""
                        )
                    },
                    ClickCallback.Options.builder()
                        .uses(1) // 1回のみ使用可能
                        .lifetime(ClickCallback.DEFAULT_LIFETIME)
                        .build()
                )
            )
            .build()
    }

    /**
     * キャンセルボタンを構築する
     */
    private fun buildCancelButton(): ActionButton {
        return ActionButton.builder(Component.text("キャンセル"))
            .build()
    }
}
