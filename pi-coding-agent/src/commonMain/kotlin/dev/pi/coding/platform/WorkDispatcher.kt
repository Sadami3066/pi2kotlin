package dev.pi.coding

import kotlinx.coroutines.CoroutineDispatcher

expect fun platformWorkDispatcher(): CoroutineDispatcher
