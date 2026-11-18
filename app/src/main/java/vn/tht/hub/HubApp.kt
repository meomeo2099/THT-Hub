package vn.tht.hub

import android.app.Application
import vn.tht.hub.vehicle.Profiles
import vn.tht.hub.vehicle.VehicleProfile

class HubApp : Application() {
    lateinit var profile: VehicleProfile
        private set

    override fun onCreate() {
        super.onCreate()
        profile = Profiles.detect()
        Permissions.grantSelf(this)
        HubService.start(this)
    }
}
