package com.bikeeyes.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActiveBicycleRoute(
    val destination: KakaoPlaceItem,
    val route: BicycleRouteResponse,
    val strategy: String = "STANDARD",
    val riskAnalysis: RouteRiskAnalysis? = null
)

object NavigationRouteState {
    private val _activeRoute = MutableStateFlow<ActiveBicycleRoute?>(null)
    val activeRoute = _activeRoute.asStateFlow()

    fun set(
        destination: KakaoPlaceItem,
        route: BicycleRouteResponse,
        strategy: String = "STANDARD",
        riskAnalysis: RouteRiskAnalysis? = null
    ) {
        _activeRoute.value = ActiveBicycleRoute(destination, route, strategy, riskAnalysis)
    }

    fun clear() {
        _activeRoute.value = null
    }
}
