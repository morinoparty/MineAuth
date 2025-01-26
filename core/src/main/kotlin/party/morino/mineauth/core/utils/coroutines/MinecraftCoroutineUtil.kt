package party.morino.mineauth.core.utils.coroutines

import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

val Dispatchers.async: CoroutineContext
    get() = DispatcherContainer.async

val Dispatchers.minecraft: CoroutineContext
    get() = DispatcherContainer.sync