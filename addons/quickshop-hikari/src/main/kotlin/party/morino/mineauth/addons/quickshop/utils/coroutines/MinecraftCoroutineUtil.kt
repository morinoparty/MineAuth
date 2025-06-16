package party.morino.mineauth.addons.quickshop.utils.coroutines

import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

val Dispatchers.async: CoroutineContext
    get() = DispatcherContainer.async

val Dispatchers.minecraft: CoroutineContext
    get() = DispatcherContainer.sync