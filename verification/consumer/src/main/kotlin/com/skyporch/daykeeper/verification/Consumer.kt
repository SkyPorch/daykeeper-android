package com.skyporch.daykeeper.verification

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import com.skyporch.daykeeper.DaykeeperClient
import com.skyporch.daykeeper.DaykeeperTokenProvider
import com.skyporch.daykeeper.ui.DaykeeperMessengerSession
import com.skyporch.daykeeper.ui.DaykeeperMessengerView

/** Compiles only against Maven artifacts, not project dependencies or SDK source. */
fun bindMessenger(context: Context, owner: LifecycleOwner, session: DaykeeperMessengerSession) =
    DaykeeperMessengerView(context).apply { bind(session, owner) }

fun customerClient(baseUrl: String, provider: DaykeeperTokenProvider) =
    DaykeeperClient(baseUrl, provider)
