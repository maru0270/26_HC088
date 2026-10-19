package com.bikeeyes.app

import android.app.Application
import com.kakao.vectormap.KakaoMapSdk

class BikeEyesApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        if (KakaoMapConfiguration.isConfigured) {
            KakaoMapSdk.init(this, KakaoMapConfiguration.nativeAppKey)
        }
    }
}

object KakaoMapConfiguration {
    val nativeAppKey: String
        get() = BuildConfig.KAKAO_NATIVE_APP_KEY

    val isConfigured: Boolean
        get() = nativeAppKey.isNotBlank()
}
