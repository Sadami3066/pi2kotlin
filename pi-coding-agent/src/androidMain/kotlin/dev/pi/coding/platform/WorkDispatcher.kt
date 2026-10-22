package dev.pi.coding

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual fun platformWorkDispatcher(): CoroutineDispatcher = Dispatchers.IO
