package party.morino.mineauth.addons.betonquest.utils.coroutines

import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * Dispatchersにminecraftプロパティを追加する拡張プロパティ
 * MCCoroutineを使わず、アドオン内で完結するディスパッチャーを提供する
 */
val Dispatchers.minecraft: CoroutineContext
    get() = DispatcherContainer.sync
