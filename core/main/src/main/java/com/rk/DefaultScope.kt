package com.rk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// App-lifetime scope. Never tied to any activity lifecycle: work launched here
// survives screen changes and is only cancelled when the process dies.
val DefaultScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
