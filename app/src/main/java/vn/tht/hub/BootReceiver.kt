package vn.tht.hub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Khởi động dịch vụ nền khi đầu xe bật hoặc sau khi cập nhật app. */
class BootReceiver : BroadcastReceiver() {
    // Khởi động xong mới áp dụng lại lớp nổi: lần chạy sớm (dịch vụ nút vô lăng kéo dậy) có thể bị từ chối cửa sổ
    override fun onReceive(c: Context, i: Intent) = HubService.start(c, HubService.ACTION_APPLY)
}
