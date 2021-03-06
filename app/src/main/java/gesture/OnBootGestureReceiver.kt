package gesture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/*
 <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
 */
class OnBootGestureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.startService(Intent(context, GestureService::class.java))
    }
}