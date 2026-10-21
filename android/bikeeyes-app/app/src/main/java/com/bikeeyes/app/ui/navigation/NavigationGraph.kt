package com.bikeeyes.app.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.bikeeyes.app.ui.screens.home.HomeScreen
import com.bikeeyes.app.ui.screens.login.LoginScreen
import com.bikeeyes.app.ui.screens.login.PasswordResetScreen
import com.bikeeyes.app.ui.screens.signup.SignupScreen
import com.bikeeyes.app.ui.screens.signupcomplete.SignupCompleteScreen
import com.bikeeyes.app.ui.screens.permission.PermissionScreen
import com.bikeeyes.app.ui.screens.cameraangle.CameraAngleGuideScreen
import com.bikeeyes.app.ui.screens.route.RouteSearchScreen
import com.bikeeyes.app.ui.screens.route.RouteSearchResultScreen
import com.bikeeyes.app.ui.screens.ride.RideInProgressScreen
import com.bikeeyes.app.ui.screens.ride.RideSummaryScreen
import com.bikeeyes.app.ui.screens.ride.RideHistoryScreen
import com.bikeeyes.app.ui.screens.ride.RideDetailScreen
import com.bikeeyes.app.ui.screens.accident.AccidentRecordListScreen
import com.bikeeyes.app.ui.screens.accident.AccidentRecordDetailScreen
import com.bikeeyes.app.ui.screens.roadrecord.RoadCollectionRecordDetailScreen
import com.bikeeyes.app.ui.screens.roadrecord.RoadCollectionRecordListScreen
import com.bikeeyes.app.ui.screens.roadrecord.ServerRoadCollectionRecordDetailScreen
import com.bikeeyes.app.ui.screens.mypage.AccountEditScreen
import com.bikeeyes.app.ui.screens.mypage.AccountDeleteScreen
import com.bikeeyes.app.ui.screens.mypage.MyPageScreen
import com.bikeeyes.app.ui.screens.mypage.NotificationSettingsScreen
import com.bikeeyes.app.ui.screens.mypage.ConsentSettingsScreen
import com.bikeeyes.app.ui.screens.mypage.PasswordChangeScreen
import com.bikeeyes.app.ui.screens.mypage.ServerSettingsScreen
import com.bikeeyes.app.ui.screens.mypage.AdminDashboardScreen
import com.bikeeyes.app.ui.screens.mypage.AdminUsersScreen
import com.bikeeyes.app.ui.screens.mypage.AdminSystemScreen
import com.bikeeyes.app.ui.screens.mypage.AdminRecordsScreen
import com.bikeeyes.app.ui.screens.mypage.AdminRideDetailScreen
import com.bikeeyes.app.ui.screens.mypage.AdminAccidentsScreen
import com.bikeeyes.app.ui.screens.mypage.AdminAccidentDetailScreen
import com.bikeeyes.app.ui.screens.mypage.AdminHazardZonesScreen
import com.bikeeyes.app.ui.screens.mypage.AdminRoadAnomalyZonesScreen
import com.bikeeyes.app.ui.screens.mypage.AdminReviewItemsScreen
import com.bikeeyes.app.ui.screens.riskzone.RiskZoneDetailScreen
import com.bikeeyes.app.ui.screens.riskzone.RiskZoneHubScreen
import com.bikeeyes.app.RideTrackingState
import com.bikeeyes.app.NavigationRouteState



@Composable
fun BikeEyesNavigationGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    onStartSensorLogging: () -> Boolean,
    onStopSensorLogging: () -> Unit,
    onDiscardSensorLogging: () -> Unit,
    onPauseSensorLogging: () -> Unit,
    onResumeSensorLogging: () -> Unit,
    areRequiredPermissionsGranted: () -> Boolean,
    onRequestRequiredPermissions: () -> Unit,
    permissionRequestResult: Boolean?
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Login.route,
        modifier = modifier
    ) {
        composable(Screen.Login.route) {
            LoginScreen(
                onLoginClick = {
                    navController.navigate(
                        if (areRequiredPermissionsGranted()) Screen.Home.route
                        else Screen.Permission.route
                    ) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onSignupClick = {
                    navController.navigate(Screen.Signup.route)
                },
                onPasswordResetClick = {
                    navController.navigate(Screen.PasswordReset.route)
                }
            )
        }

        composable(Screen.PasswordReset.route) {
            PasswordResetScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.Home.route) {
            HomeScreen(
                onStartRideClick = {
                    NavigationRouteState.clear()
                    navController.navigate(Screen.CameraGuide.route)
                },
                onRouteSearchClick = {
                    navController.navigate(Screen.Route.route)
                },
                onRideHistoryClick = {
                    navController.navigate(Screen.History.route)
                }
            )
        }

        composable(Screen.Route.route) {
            RouteSearchScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                onStartNavigationClick = {
                    navController.navigate(
                        Screen.RouteSearchResult.route
                    )
                }
            )
        }

        composable(Screen.Ride.route) {
            RideInProgressScreen(
                onPauseRideClick = onPauseSensorLogging,
                onResumeRideClick = onResumeSensorLogging,
                onFinishRideClick = {
                    onStopSensorLogging()
                    NavigationRouteState.clear()

                    navController.navigate(Screen.RideSummary.route) {
                        popUpTo(Screen.Ride.route) {
                            inclusive = true
                        }

                        launchSingleTop = true
                    }
                },
                onDiscardRideClick = {
                    onDiscardSensorLogging()
                    NavigationRouteState.clear()
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Screen.RideSummary.route) {
            RideSummaryScreen(
                onDetailClick = {
                    RideTrackingState.lastRideResult.value?.rideSessionId?.let { sessionId ->
                        navController.navigate(Screen.RideDetail.createRoute(sessionId))
                    }
                },
                onGoHomeClick = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) {
                            inclusive = false
                        }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Screen.History.route) {
            RideHistoryScreen(
                onRideClick = { sessionId ->
                    navController.navigate(Screen.RideDetail.createRoute(sessionId))
                }
            )
        }

        composable(Screen.RideDetail.route) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId").orEmpty()
            RideDetailScreen(
                sessionId = sessionId,
                onRoadRecordsClick = {
                    navController.navigate(Screen.RideRoadRecords.createRoute(sessionId))
                },
                onAccidentRecordsClick = {
                    navController.navigate(Screen.RideAccidents.createRoute(sessionId))
                },
                onDeleted = {
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.Danger.route) {
            RiskZoneHubScreen(
                onZoneClick = { zoneId ->
                    navController.navigate(Screen.RiskZoneDetail.createRoute(zoneId))
                }
            )
        }

        composable(Screen.RiskZoneDetail.route) { backStackEntry ->
            RiskZoneDetailScreen(
                zoneId = backStackEntry.arguments?.getString("zoneId").orEmpty()
            )
        }

        composable(Screen.RoadRecordDetail.route) { backStackEntry ->
            val recordId = backStackEntry.arguments?.getString("recordId")?.toLongOrNull() ?: -1L
            RoadCollectionRecordDetailScreen(recordId = recordId)
        }

        composable(Screen.ServerRoadRecordDetail.route) { backStackEntry ->
            ServerRoadCollectionRecordDetailScreen(
                sessionId = backStackEntry.arguments?.getString("sessionId").orEmpty(),
                serverRecordKey = backStackEntry.arguments?.getString("serverRecordKey").orEmpty()
            )
        }

        composable(Screen.RideRoadRecords.route) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId").orEmpty()
            RoadCollectionRecordListScreen(
                sessionId = sessionId,
                onRecordClick = { localRecordId, serverRecordKey ->
                    when {
                        !serverRecordKey.isNullOrBlank() -> navController.navigate(
                            Screen.ServerRoadRecordDetail.createRoute(sessionId, serverRecordKey)
                        )
                        localRecordId != null -> navController.navigate(
                            Screen.RoadRecordDetail.createRoute(localRecordId)
                        )
                    }
                }
            )
        }

        composable(Screen.RideAccidents.route) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId").orEmpty()
            AccidentRecordListScreen(
                sessionId = sessionId,
                onEventClick = { eventUuid ->
                    navController.navigate(Screen.AccidentDetail.createRoute(eventUuid))
                }
            )
        }

        composable(Screen.AccidentDetail.route) { backStackEntry ->
            AccidentRecordDetailScreen(
                eventUuid = backStackEntry.arguments?.getString("eventUuid").orEmpty()
            )
        }

        composable(Screen.MyPage.route) {
            MyPageScreen(
                onRideHistoryClick = { navController.navigate(Screen.History.route) },
                onNotificationSettingsClick = { navController.navigate(Screen.NotificationSettings.route) },
                onConsentSettingsClick = { navController.navigate(Screen.ConsentSettings.route) },
                onServerSettingsClick = { navController.navigate(Screen.ServerSettings.route) },
                onAccountEditClick = { navController.navigate(Screen.AccountEdit.route) },
                onPasswordChangeClick = { navController.navigate(Screen.PasswordChange.route) },
                onAccountDeleteClick = { navController.navigate(Screen.AccountDelete.route) },
                onAdminDiagnosticsClick = { navController.navigate(Screen.AdminDiagnostics.route) },
                onLogoutClick = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Screen.NotificationSettings.route) {
            NotificationSettingsScreen(onSaved = { navController.popBackStack() })
        }

        composable(Screen.ConsentSettings.route) {
            ConsentSettingsScreen()
        }
        composable(Screen.AdminDiagnostics.route) {
            AdminDashboardScreen(
                onUsersClick = { navController.navigate(Screen.AdminUsers.route) },
                onRidesClick = { navController.navigate(Screen.AdminRides.route) },
                onAccidentsClick = { navController.navigate(Screen.AdminAccidents.route) },
                onRoadAnomalyZonesClick = {
                    navController.navigate(Screen.AdminRoadAnomalyZones.route)
                },
                onHazardZonesClick = {
                    navController.navigate(Screen.AdminHazardZones.route)
                },
                onReviewItemsClick = {
                    navController.navigate(Screen.AdminReviewItems.route)
                },
                onSystemClick = { navController.navigate(Screen.AdminSystem.route) }
            )
        }
        composable(Screen.AdminUsers.route) {
            AdminUsersScreen(
                onBack = { navController.popBackStack() },
                onDashboard = {
                    navController.navigate(Screen.AdminDiagnostics.route) {
                        popUpTo(Screen.AdminDiagnostics.route) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(Screen.AdminSystem.route) {
            AdminSystemScreen(
                onBack = { navController.popBackStack() },
                onDashboard = {
                    navController.navigate(Screen.AdminDiagnostics.route) {
                        popUpTo(Screen.AdminDiagnostics.route) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(Screen.AdminRides.route) {
            AdminRecordsScreen(
                onRideClick = { sessionId ->
                    navController.navigate(Screen.AdminRideDetail.createRoute(sessionId))
                },
                onBack = { navController.popBackStack() },
                onDashboard = {
                    navController.navigate(Screen.AdminDiagnostics.route) {
                        popUpTo(Screen.AdminDiagnostics.route) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(Screen.AdminRideDetail.route) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId").orEmpty()
            AdminRideDetailScreen(
                sessionId = sessionId,
                onAccidentClick = { eventUuid ->
                    navController.navigate(
                        Screen.AdminAccidentDetail.createRoute(
                            sessionId = sessionId,
                            responseFilter = "ALL",
                            eventUuid = eventUuid
                        )
                    )
                },
                onBack = { navController.popBackStack() },
                onDashboard = {
                    navController.navigate(Screen.AdminDiagnostics.route) {
                        popUpTo(Screen.AdminDiagnostics.route) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(Screen.AdminAccidents.route) {
            AdminAccidentsScreen(
                onAccidentsClick = { sessionId, responseFilter ->
                    navController.navigate(Screen.AdminAccidentDetail.createRoute(sessionId, responseFilter))
                },
                onBack = { navController.popBackStack() },
                onDashboard = {
                    navController.navigate(Screen.AdminDiagnostics.route) {
                        popUpTo(Screen.AdminDiagnostics.route) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(
            route = Screen.AdminAccidentDetail.route,
            arguments = listOf(
                navArgument("eventUuid") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            AdminAccidentDetailScreen(
                sessionId = backStackEntry.arguments?.getString("sessionId").orEmpty(),
                initialFilter = backStackEntry.arguments?.getString("responseFilter") ?: "ALL",
                initialEventUuid = backStackEntry.arguments?.getString("eventUuid"),
                onBack = { navController.popBackStack() },
                onDashboard = {
                    navController.navigate(Screen.AdminDiagnostics.route) {
                        popUpTo(Screen.AdminDiagnostics.route) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(Screen.AdminHazardZones.route) {
            AdminHazardZonesScreen(
                onBack = { navController.popBackStack() },
                onDashboard = {
                    navController.navigate(Screen.AdminDiagnostics.route) {
                        popUpTo(Screen.AdminDiagnostics.route) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(Screen.AdminRoadAnomalyZones.route) {
            AdminRoadAnomalyZonesScreen(
                onBack = { navController.popBackStack() },
                onDashboard = {
                    navController.navigate(Screen.AdminDiagnostics.route) {
                        popUpTo(Screen.AdminDiagnostics.route) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(Screen.AdminReviewItems.route) {
            AdminReviewItemsScreen()
        }

        composable(Screen.ServerSettings.route) {
            ServerSettingsScreen(onSaved = { navController.popBackStack() })
        }

        composable(Screen.AccountEdit.route) {
            AccountEditScreen(onSaved = { navController.popBackStack() })
        }

        composable(Screen.PasswordChange.route) {
            PasswordChangeScreen(
                onChanged = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Screen.AccountDelete.route) {
            AccountDeleteScreen(
                onDeleted = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Screen.Signup.route) {
            SignupScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                onSignupClick = {
                    navController.navigate(Screen.SignupComplete.route)
                }
            )
        }

        composable(Screen.SignupComplete.route) {
            SignupCompleteScreen(
                onGoLoginClick = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(Screen.Login.route) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Screen.Permission.route) {
            LaunchedEffect(permissionRequestResult) {
                if (permissionRequestResult == true) {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Permission.route) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
            PermissionScreen(
                onAllowAllClick = {
                    onRequestRequiredPermissions()
                },
                onPermissionAllowClick = {
                    onRequestRequiredPermissions()
                },
                onLaterClick = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Permission.route) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Screen.CameraGuide.route) {
            CameraAngleGuideScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                onStartRideClick = {
                    val serviceStarted = onStartSensorLogging()

                    if (serviceStarted) {
                        navController.navigate(Screen.Ride.route)
                    }
                }
            )
        }

        composable(Screen.RouteSearchResult.route) {
            RouteSearchResultScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                onLocationClick = {
                    // TODO
                },
                onStartNavigationClick = {
                    navController.navigate(Screen.CameraGuide.route)
                }
            )
        }
    }
}
