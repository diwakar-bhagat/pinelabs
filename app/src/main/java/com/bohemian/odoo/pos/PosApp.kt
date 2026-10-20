package com.bohemian.odoo.pos

import android.app.Application
import com.bohemian.odoo.pos.di.AppContainer
import com.bohemian.odoo.pos.pine.PineSdkBootstrap

class PosApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Pine SDK: init exactly once per process (AppToApp + registered Application ID).
        PineSdkBootstrap.init(this, BuildConfig.PINE_APPLICATION_ID)
        container = AppContainer(this)
        container.startBackgroundWork()
    }
}
