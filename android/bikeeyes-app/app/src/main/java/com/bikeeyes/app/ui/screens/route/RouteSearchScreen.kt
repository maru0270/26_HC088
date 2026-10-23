package com.bikeeyes.app.ui.screens.route

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bikeeyes.app.KakaoPlaceItem
import com.bikeeyes.app.BikeEyesLocalDatabase
import com.bikeeyes.app.BicycleRouteResponse
import com.bikeeyes.app.NavigationRouteState
import com.bikeeyes.app.NetworkModule
import com.bikeeyes.app.RiskAwareRoutePlanner
import com.bikeeyes.app.RouteRiskAnalysis
import com.bikeeyes.app.ui.components.BikeEyesPrimaryButton
import com.bikeeyes.app.ui.components.BikeEyesKakaoMap
import com.bikeeyes.app.ui.components.BikeEyesMapMarker
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun RouteSearchScreen(
    onBackClick: () -> Unit = {},
    onStartNavigationClick: () -> Unit = {}
) {
    var startLocation by remember { mutableStateOf("") }
    var currentLocationName by remember { mutableStateOf("현재 위치를 확인하는 중입니다") }
    var destination by remember { mutableStateOf("") }
    var selectedStartLocation by remember { mutableStateOf<KakaoPlaceItem?>(null) }
    var selectedDestination by remember { mutableStateOf<KakaoPlaceItem?>(null) }
    var startSuggestions by remember { mutableStateOf<List<KakaoPlaceItem>>(emptyList()) }
    var placeSuggestions by remember { mutableStateOf<List<KakaoPlaceItem>>(emptyList()) }
    var startLocationFocused by remember { mutableStateOf(false) }
    var destinationFocused by remember { mutableStateOf(false) }
    var startSearchError by remember { mutableStateOf<String?>(null) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var currentLatitude by remember { mutableStateOf<Double?>(null) }
    var currentLongitude by remember { mutableStateOf<Double?>(null) }
    var routeLoading by remember { mutableStateOf(false) }
    var routeError by remember { mutableStateOf<String?>(null) }
    var pendingRouteRequest by remember { mutableStateOf<PendingRouteRequest?>(null) }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val screenScrollState = rememberScrollState()
    val keyboardVisible = WindowInsets.isImeVisible

    LaunchedEffect(currentLatitude, currentLongitude) {
        val latitude = currentLatitude ?: return@LaunchedEffect
        val longitude = currentLongitude ?: return@LaunchedEffect
        runCatching {
            NetworkModule.dynamicSensorApi(context).getCurrentLocationName(
                longitude = longitude,
                latitude = latitude
            )
        }.onSuccess { location ->
            currentLocationName = location.name.ifBlank {
                location.road_address.ifBlank {
                    location.address.ifBlank { "현재 위치" }
                }
            }
        }.onFailure {
            currentLocationName = "현재 위치"
        }
    }

    LaunchedEffect(destination, selectedDestination) {
        val query = destination.trim()
        if (query.length < 2 || selectedDestination?.name == query) {
            placeSuggestions = emptyList()
            searchError = null
            return@LaunchedEffect
        }

        delay(400)
        runCatching {
            NetworkModule.dynamicSensorApi(context).searchPlaces(
                query = query,
                longitude = currentLongitude,
                latitude = currentLatitude,
                size = 15
            )
        }.onSuccess { response ->
            if (destination.trim() == query) {
                placeSuggestions = response.places
                searchError = if (response.places.isEmpty()) {
                    "검색 결과가 없습니다. 장소명이나 도로명 주소를 다시 확인해 주세요."
                } else {
                    null
                }
            }
        }.onFailure { error ->
            placeSuggestions = emptyList()
            searchError = placeSearchErrorMessage(error)
        }
    }

    LaunchedEffect(startLocation, selectedStartLocation) {
        val query = startLocation.trim()
        if (query.length < 2 || selectedStartLocation?.name == query) {
            startSuggestions = emptyList()
            startSearchError = null
            return@LaunchedEffect
        }

        delay(400)
        runCatching {
            NetworkModule.dynamicSensorApi(context).searchPlaces(
                query = query,
                longitude = currentLongitude,
                latitude = currentLatitude,
                size = 15
            )
        }.onSuccess { response ->
            if (startLocation.trim() == query) {
                startSuggestions = response.places
                startSearchError = if (response.places.isEmpty()) {
                    "검색 결과가 없습니다. 장소명이나 도로명 주소를 다시 확인해 주세요."
                } else {
                    null
                }
            }
        }.onFailure { error ->
            startSuggestions = emptyList()
            startSearchError = placeSearchErrorMessage(error)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(screenScrollState)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text(
            text = "경로 탐색",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "출발지",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = startLocation,
                onValueChange = {
                    startLocation = it
                    selectedStartLocation = null
                    routeError = null
                    NavigationRouteState.clear()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { startLocationFocused = it.isFocused },
                placeholder = {
                    Text(
                        text = currentLocationName,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                    )
                },
                supportingText = startSearchError?.let { message -> { Text(message) } },
                singleLine = true
            )

            if (startLocationFocused && startSuggestions.isNotEmpty()) {
                PlaceSuggestionList(
                    places = startSuggestions,
                    onPlaceSelected = { place ->
                        startLocation = place.name
                        selectedStartLocation = place
                        startSuggestions = emptyList()
                        focusManager.clearFocus()
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "목적지",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = destination,
                onValueChange = {
                    destination = it
                    selectedDestination = null
                    routeError = null
                    NavigationRouteState.clear()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { destinationFocused = it.isFocused },
                placeholder = { Text("목적지를 입력하세요") },
                supportingText = searchError?.let { message -> { Text(message) } },
                singleLine = true
            )

            if (destinationFocused && placeSuggestions.isNotEmpty()) {
                PlaceSuggestionList(
                    places = placeSuggestions,
                    onPlaceSelected = { place ->
                        destination = place.name
                        selectedDestination = place
                        placeSuggestions = emptyList()
                        focusManager.clearFocus()
                    }
                )
            }
        }

        run {
            Spacer(modifier = Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
            ) {
                RouteMap(
                    modifier = Modifier.fillMaxSize(),
                    selectedStartLocation = selectedStartLocation,
                    selectedDestination = selectedDestination,
                    onLocationChanged = { latitude, longitude ->
                        currentLatitude = latitude
                        currentLongitude = longitude
                    }
                )
                if (keyboardVisible) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(screenScrollState) {
                                detectVerticalDragGestures { change, dragAmount ->
                                    change.consume()
                                    scope.launch {
                                        screenScrollState.scrollBy(-dragAmount)
                                    }
                                }
                            }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            routeError?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            BikeEyesPrimaryButton(
                text = if (routeLoading) "경로를 찾는 중..." else "경로 안내 시작",
                onClick = {
                    if (routeLoading) return@BikeEyesPrimaryButton
                    val currentLat = currentLatitude
                    val currentLon = currentLongitude
                    when {
                        startLocation.isBlank() && (currentLat == null || currentLon == null) ->
                            routeError = "현재 GPS 위치를 확인한 후 다시 시도해 주세요."
                        startLocation.isNotBlank() && startLocation.trim().length < 2 ->
                            routeError = "출발지를 두 글자 이상 입력해 주세요."
                        destination.trim().length < 2 ->
                            routeError = "목적지를 두 글자 이상 입력해 주세요."
                        else -> {
                            routeLoading = true
                            routeError = null
                            scope.launch {
                                runCatching {
                                    val start = if (startLocation.isBlank()) {
                                        null
                                    } else {
                                        selectedStartLocation
                                            ?: NetworkModule.dynamicSensorApi(context).searchPlaces(
                                                query = startLocation.trim(),
                                                longitude = currentLon,
                                                latitude = currentLat,
                                                size = 15
                                            ).places.firstOrNull()
                                            ?: error("start not found")
                                    }
                                    val startLat = start?.latitude
                                        ?: currentLat
                                        ?: error("current location unavailable")
                                    val startLon = start?.longitude
                                        ?: currentLon
                                        ?: error("current location unavailable")
                                    if (start != null) {
                                        selectedStartLocation = start
                                        startLocation = start.name
                                    }
                                    val target = selectedDestination
                                        ?: NetworkModule.dynamicSensorApi(context).searchPlaces(
                                            query = destination.trim(),
                                            longitude = startLon,
                                            latitude = startLat,
                                            size = 15
                                        ).places.firstOrNull()
                                        ?: error("destination not found")
                                    PendingRouteRequest(startLat, startLon, target)
                                }.onSuccess { request ->
                                    val target = request.destination
                                    selectedDestination = target
                                    destination = target.name
                                    pendingRouteRequest = request
                                }.onFailure { error ->
                                    routeError = routeSearchErrorMessage(error)
                                }
                                routeLoading = false
                            }
                        }
                    }
                }
            )
        }
    }

    pendingRouteRequest?.let { request ->
        ModalBottomSheet(onDismissRequest = { pendingRouteRequest = null }) {
            RouteChoiceBottomSheet(
                request = request,
                onRouteSelected = { candidate ->
                    val route = candidate.route ?: return@RouteChoiceBottomSheet
                    NavigationRouteState.set(
                        destination = request.destination,
                        route = route,
                        strategy = if (candidate.riskAnalysis != null) "RISK_AVOID" else "STANDARD",
                        riskAnalysis = candidate.riskAnalysis
                    )
                    pendingRouteRequest = null
                    onStartNavigationClick()
                }
            )
        }
    }
}

private data class PendingRouteRequest(
    val startLatitude: Double,
    val startLongitude: Double,
    val destination: KakaoPlaceItem
)

private enum class RouteChoiceStep { TYPE, OPTION }

private data class RouteOptionDefinition(
    val label: String,
    val routeMode: String,
    val isRiskAvoidance: Boolean = false
)

private data class RouteOptionCandidate(
    val definition: RouteOptionDefinition,
    val route: BicycleRouteResponse? = null,
    val riskAnalysis: RouteRiskAnalysis? = null,
    val error: String? = null
)

private suspend fun loadRouteCandidates(
    context: android.content.Context,
    request: PendingRouteRequest,
    routeType: String
): List<RouteOptionCandidate> = coroutineScope {
    val api = NetworkModule.dynamicSensorApi(context)
    val definitions = if (routeType == "WALKING_BIKE") {
        listOf(
            RouteOptionDefinition("넓은 길 우선", "BROAD_FIRST"),
            RouteOptionDefinition("최단거리", "SHORTEST"),
            RouteOptionDefinition("평지 우선", "ACCESSIBLE")
        )
    } else {
        listOf(
            RouteOptionDefinition("최단거리", "SHORTEST"),
            RouteOptionDefinition("평지 우선", "ACCESSIBLE"),
            RouteOptionDefinition("위험 회피", "BIKE_ONLY", isRiskAvoidance = true)
        )
    }

    definitions.map { definition ->
        async {
            runCatching {
                val riskPlan = if (definition.isRiskAvoidance) {
                    RiskAwareRoutePlanner.plan(
                        api = api,
                        riskZoneDao = BikeEyesLocalDatabase.getInstance(context).riskZoneDao(),
                        startLongitude = request.startLongitude,
                        startLatitude = request.startLatitude,
                        endLongitude = request.destination.longitude,
                        endLatitude = request.destination.latitude,
                        destinationName = request.destination.name
                    )
                } else {
                    null
                }
                val route = riskPlan?.route ?: if (routeType == "WALKING_BIKE") {
                    api.getWalkingBikeRoute(
                        startLongitude = request.startLongitude,
                        startLatitude = request.startLatitude,
                        endLongitude = request.destination.longitude,
                        endLatitude = request.destination.latitude,
                        destinationName = request.destination.name,
                        routeMode = definition.routeMode
                    )
                } else {
                    api.getBicycleRoute(
                        startLongitude = request.startLongitude,
                        startLatitude = request.startLatitude,
                        endLongitude = request.destination.longitude,
                        endLatitude = request.destination.latitude,
                        destinationName = request.destination.name,
                        routeMode = definition.routeMode
                    )
                }
                RouteOptionCandidate(
                    definition = definition,
                    route = route,
                    riskAnalysis = riskPlan?.analysis
                )
            }.getOrElse { error ->
                RouteOptionCandidate(definition = definition, error = routeSearchErrorMessage(error))
            }
        }
    }.awaitAll()
}

@Composable
private fun RouteChoiceBottomSheet(
    request: PendingRouteRequest,
    onRouteSelected: (RouteOptionCandidate) -> Unit
) {
    val context = LocalContext.current
    var step by remember(request) { mutableStateOf(RouteChoiceStep.TYPE) }
    var routeType by remember(request) { mutableStateOf("BICYCLE") }
    var loading by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf<List<RouteOptionCandidate>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(step, routeType, request) {
        if (step != RouteChoiceStep.OPTION) return@LaunchedEffect
        loading = true
        error = null
        candidates = emptyList()
        runCatching { loadRouteCandidates(context, request, routeType) }
            .onSuccess { candidates = it }
            .onFailure { error = routeSearchErrorMessage(it) }
        loading = false
    }

    AnimatedContent(
        targetState = step,
        transitionSpec = {
            slideInHorizontally(animationSpec = tween(260)) { it } togetherWith
                slideOutHorizontally(animationSpec = tween(260)) { -it }
        },
        label = "route-choice-step"
    ) { currentStep ->
        when (currentStep) {
            RouteChoiceStep.TYPE -> RouteTypeChoices(
                destinationName = request.destination.name,
                onTypeSelected = {
                    routeType = it
                    step = RouteChoiceStep.OPTION
                }
            )
            RouteChoiceStep.OPTION -> RouteOptionChoices(
                routeType = routeType,
                candidates = candidates,
                loading = loading,
                error = error,
                onBack = { step = RouteChoiceStep.TYPE },
                onRouteSelected = onRouteSelected
            )
        }
    }
}

@Composable
private fun RouteTypeChoices(destinationName: String, onTypeSelected: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("경로 유형 선택", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("${destinationName}까지 이동할 경로 유형을 선택하세요.", style = MaterialTheme.typography.bodyMedium)
        RouteTypeChoiceCard(
            title = "자전거도로 우선",
            description = "자전거 주행에 적합한 경로를 우선으로 비교합니다.",
            onClick = { onTypeSelected("BICYCLE") }
        )
        RouteTypeChoiceCard(
            title = "일반도로 포함",
            description = "일반도로·보행로 등을 포함한 이동 가능 경로를 비교합니다.",
            onClick = { onTypeSelected("WALKING_BIKE") }
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun RouteTypeChoiceCard(title: String, description: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RouteOptionChoices(
    routeType: String,
    candidates: List<RouteOptionCandidate>,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onRouteSelected: (RouteOptionCandidate) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            if (routeType == "BICYCLE") "자전거도로 우선 경로" else "일반도로 포함 경로",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text("옵션별 거리와 예상 시간을 비교한 뒤 하나를 선택하세요.", style = MaterialTheme.typography.bodySmall)
        if (routeType == "WALKING_BIKE") {
            Text(
                "일반도로에서는 도로 구조·속도 차이로 노면 위험요소 객체 탐지 결과가 정확하지 않거나 일부 누락될 수 있습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = androidx.compose.ui.graphics.Color(0xFF9A5A00)
            )
        }
        if (loading) {
            CircularProgressIndicator()
            Text("옵션별 경로를 계산하는 중입니다.")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        candidates.forEach { candidate ->
            key(candidate.definition.routeMode, candidate.route?.route_mode, candidate.error) {
                RouteOptionCandidateCard(candidate, onRouteSelected)
            }
        }
        Text(
            "‹ 경로 유형 다시 선택",
            modifier = Modifier.clickable(onClick = onBack).padding(vertical = 12.dp),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun RouteOptionCandidateCard(
    candidate: RouteOptionCandidate,
    onRouteSelected: (RouteOptionCandidate) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = candidate.route != null) {
            onRouteSelected(candidate)
        },
        shape = RoundedCornerShape(16.dp),
        color = if (candidate.route != null) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(candidate.definition.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val route = candidate.route
            if (route != null) {
                Text(
                    "${formatDistance(route.total_distance_meters)} · ${formatRouteDuration(route.total_duration_seconds)}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                candidate.riskAnalysis?.takeUnless { it.fallbackUsed }?.let { analysis ->
                    Text(
                        "위험구간 ${analysis.intersectingZoneCount}곳 · 위험 점수 ${String.format("%.1f", analysis.riskScore)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                Text(candidate.error ?: "경로를 계산하지 못했습니다.", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun PlaceSuggestionList(
    places: List<KakaoPlaceItem>,
    onPlaceSelected: (KakaoPlaceItem) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp),
        shape = RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp),
        color = androidx.compose.ui.graphics.Color.White,
        shadowElevation = 8.dp,
        tonalElevation = 2.dp
    ) {
        LazyColumn {
            items(places, key = { it.id }) { place ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPlaceSelected(place) }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = place.name,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = buildString {
                            append(place.road_address.ifBlank { place.address })
                            place.distance_meters?.let { distance ->
                                if (isNotEmpty()) append(" · ")
                                append(formatDistance(distance))
                            }
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun RouteMap(
    modifier: Modifier = Modifier,
    selectedStartLocation: KakaoPlaceItem?,
    selectedDestination: KakaoPlaceItem?,
    onLocationChanged: (latitude: Double, longitude: Double) -> Unit
) {
    BikeEyesKakaoMap(
        modifier = modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp)),
        markers = buildList {
            selectedStartLocation?.let { start ->
                add(
                    BikeEyesMapMarker(
                        id = "selected-start",
                        latitude = start.latitude,
                        longitude = start.longitude
                    )
                )
            }
            selectedDestination?.let { destination ->
                add(
                BikeEyesMapMarker(
                    id = "selected-destination",
                    latitude = destination.latitude,
                    longitude = destination.longitude
                )
                )
            }
        },
        onLocationChanged = onLocationChanged
    )
}

private fun formatDistance(distanceMeters: Int): String =
    if (distanceMeters < 1_000) {
        "${distanceMeters}m"
    } else {
        String.format(java.util.Locale.KOREA, "%.1fkm", distanceMeters / 1_000.0)
    }

private fun formatRouteDuration(durationSeconds: Int): String {
    val minutes = (durationSeconds / 60).coerceAtLeast(1)
    return if (minutes < 60) {
        "약 ${minutes}분"
    } else {
        "약 ${minutes / 60}시간 ${minutes % 60}분"
    }
}

private fun placeSearchErrorMessage(error: Throwable): String = when {
    error is IOException ->
        "장소 검색 서버에 연결할 수 없습니다. 서버 실행 상태와 네트워크를 확인해 주세요."
    error is HttpException && error.code() == 503 ->
        "서버에 카카오 REST API 키가 설정되지 않았습니다."
    error is HttpException && error.code() in 500..599 ->
        "카카오 장소 검색이 일시적으로 불안정합니다. 잠시 후 다시 시도해 주세요."
    else ->
        "장소를 검색하지 못했습니다. 입력 내용을 확인한 후 다시 시도해 주세요."
}

private fun routeSearchErrorMessage(error: Throwable): String = when {
    error.message == "start not found" ->
        "출발지를 찾지 못했습니다. 장소명이나 도로명 주소를 다시 확인해 주세요."
    error.message == "current location unavailable" ->
        "현재 GPS 위치를 확인한 후 다시 시도해 주세요."
    error.message == "destination not found" ->
        "목적지를 찾지 못했습니다. 장소명이나 도로명 주소를 다시 확인해 주세요."
    error is IOException ->
        "경로 검색 서버에 연결할 수 없습니다. 서버 실행 상태와 네트워크를 확인해 주세요."
    error is HttpException && error.code() == 404 ->
        "선택한 출발지와 목적지 사이에서 이용 가능한 경로를 찾지 못했습니다."
    error is HttpException && error.code() == 503 ->
        "서버에 카카오 REST API 키가 설정되지 않았습니다."
    error is HttpException && error.code() in 500..599 ->
        "카카오 경로 검색이 일시적으로 불안정합니다. 잠시 후 다시 시도해 주세요."
    else ->
        "경로를 검색하지 못했습니다. 목적지와 경로 옵션을 확인해 주세요."
}
