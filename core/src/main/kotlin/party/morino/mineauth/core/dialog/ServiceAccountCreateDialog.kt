package party.morino.mineauth.core.dialog

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.event.ClickCallback
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.entity.Player

/**
 * サービスアカウント作成ダイアログ
 * プレイヤーにフォームを表示し、サービス名の入力を受け付ける
 */
object ServiceAccountCreateDialog {
    // 入力フィールドのキー定数
    private const val KEY_SERVICE_NAME = "service_name"

    // 入力フィールドの幅
    private const val INPUT_WIDTH_NAME = 300

    // 入力フィールドの最大長
    private const val MAX_LENGTH_NAME = 64

    /**
     * ダイアログを表示する
     * @param player 表示対象のプレイヤー
     */
    fun show(player: Player) {
        val dialog = Dialog.create { factory ->
            factory.empty()
                .base(
                    DialogBase.builder(Component.text("サービスアカウント作成"))
                        .canCloseWithEscape(true)
                        .body(
                            listOf(
                                DialogBody.plainMessage(
                                    MiniMessage.miniMessage().deserialize(
                                        "新しいサービスアカウントを作成します。<newline>サービス名を入力してください。"
                                    )
                                )
                            )
                        )
                        .inputs(buildInputs())
                        .build()
                )
                .type(
                    DialogType.confirmation(
                        buildCreateButton(player),
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
            // サービス名（テキスト入力）
            DialogInput.text(KEY_SERVICE_NAME, Component.text("サービス名"))
                .width(INPUT_WIDTH_NAME)
                .initial("")
                .maxLength(MAX_LENGTH_NAME)
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
                        val serviceName = view.getText(KEY_SERVICE_NAME)

                        // 非同期で作成処理を実行
                        ServiceAccountCreateHandler.handleCreate(
                            player,
                            serviceName ?: ""
                        )
                    },
                    ClickCallback.Options.builder()
                        .uses(1)
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
