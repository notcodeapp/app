package com.notcode.mobile.ui

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import com.notcode.mobile.IdeViewModel

internal fun registerPushToken(vm: IdeViewModel, context: Context) {
    val prefs = context.getSharedPreferences("push", Context.MODE_PRIVATE)
    val saved = prefs.getString("token", null)
    if (saved != null) vm.registerPushToken(saved)
    FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
        prefs.edit().putString("token", token).apply()
        vm.registerPushToken(token)
    }
}
