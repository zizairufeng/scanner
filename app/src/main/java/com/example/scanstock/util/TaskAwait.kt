package com.example.scanstock.util

import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 把 Play Services 的 [Task] 桥接成挂起函数。
 *
 * 为什么自己写而不引入 kotlinx-coroutines-play-services：
 * 只为了一个 await() 就多一个依赖不值当，且本项目要尽量少的第三方库。
 */
suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { value ->
        if (cont.isActive) cont.resume(value)
    }
    addOnFailureListener { e ->
        if (cont.isActive) cont.resumeWithException(e)
    }
    addOnCanceledListener {
        cont.cancel()
    }
}
