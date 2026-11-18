package vn.tht.hub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Khởi động dịch vụ nền khi đầu xe bật hoặc sau khi cập nhật app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) = HubService.start(c)
}
