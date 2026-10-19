package com.andrerinas.openheadunit.main

import android.content.Context
import android.content.Intent
import android.annotation.SuppressLint
import android.hardware.usb.UsbManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.graphics.Color
import android.content.res.ColorStateList
import android.widget.*
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewTreeObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.PackageManager
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.drawable.Drawable
import android.hardware.usb.UsbDevice
import android.net.ConnectivityManager
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import android.os.Build
import android.bluetooth.BluetoothDevice
import android.os.CountDownTimer
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.DriverCandidatePolicy
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.PokeTargetPolicy
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.ExternalBtTransportPolicy
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeAaHandshakeManager
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeDriverSelectionPolicy
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapProjectionActivity
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.wifi.modes.helper.NearbyManager
import com.andrerinas.openheadunit.connection.usb.UsbDeviceCompat
import com.andrerinas.openheadunit.connection.usb.UsbDeviceDiagnostics
import android.content.res.Configuration
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt.ZbtDaemonReachability
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.AppPermissions
import com.andrerinas.openheadunit.utils.CarLauncherManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.ColorUtils
import com.andrerinas.openheadunit.utils.HomeUiHelper
import com.andrerinas.openheadunit.utils.ClassicHomeUiHelper
import com.andrerinas.openheadunit.utils.ToastUtils
import com.andrerinas.openheadunit.utils.VpnControl
import com.andrerinas.openheadunit.connection.self.SelfLaunchPath
import com.andrerinas.openheadunit.connection.self.SelfLaunchRoutePolicy
import com.andrerinas.openheadunit.connection.self.SelfLauncherManager
import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.andrerinas.openheadunit.location.LocationHolder
import com.andrerinas.openheadunit.connection.usb.UsbReceiver
import com.andrerinas.openheadunit.connection.usb.UsbAccessoryMode
import com.andrerinas.openheadunit.connection.wifi.modes.helper.HelperStrategy
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode
import com.andrerinas.openheadunit.connection.wifi.WirelessSelectionPolicy
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

class HomeFragment : Fragment() {

    private val commManager get() = App.provide(requireContext()).commManager

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            AppLog.i("VPN permission granted. Starting DummyVpnService and Self Mode.")
            VpnControl.startVpn(requireContext());
            startSelfModeInternal()
        } else {
            AppLog.w("VPN permission denied. Offline Self Mode might fail.")
            ToastUtils.showToast(requireContext(), getString(R.string.failed_start_android_auto), Toast.LENGTH_LONG, force = true)
        }
    }

    private val bluetoothPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            showNativeAaDeviceSelector()
        } else {
            ToastUtils.showToast(requireContext(), R.string.bt_permission_denied, Toast.LENGTH_LONG, force = true)
        }
    }

    private val mainViewModel: MainViewModel by activityViewModels()

    private lateinit var selfModeTile: View
    private lateinit var usbTile: View
    private lateinit var settingsTile: View
    private lateinit var wifiTile: View
    private lateinit var exitButton: View
    private lateinit var appsButton: View
    private var primaryAction: View? = null
    private var primaryCancel: View? = null
    private lateinit var favoriteSlots: List<View>
    private var isClassicHome = false
    private var classicGridListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var decision: HomePrimaryPolicy.Decision? = null
    private var cachedPalette: HomeUiHelper.Palette? = null
    private var reconnectCountdown: Job? = null
    private var reconnectTarget: HomePrimaryPolicy.Connection? = null
    private var reconnectSecondsLeft = 0
    private var autoConnectCancelled = false
    private var hasAttemptedAutoConnect = false
    private var hasAttemptedSingleUsbAutoConnect = false
    private var activeDialog: androidx.appcompat.app.AlertDialog? = null
    private var driverCountdownTimer: CountDownTimer? = null
    private var homeInfoTicker: Job? = null
    private var gpsListener: LocationListener? = null
    private var gpsSpeedKmh: Int? = null
    /** The launcher home on a small screen, where the main button's card gives way to the rest. */
    private var compactHome = false

    // The widget the system is asking the user to allow, and the answer coming back.
    private var pendingWidgetId = -1

    private val widgetBindLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val ctx = context ?: return@registerForActivityResult
        val returned = result.data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
        val id = if (returned >= 0) returned else pendingWidgetId
        pendingWidgetId = -1
        if (result.resultCode == android.app.Activity.RESULT_OK && id >= 0) {
            placeWidget(id)
        } else {
            if (id >= 0) HomeWidgets.forget(ctx, id)
            AppLog.i("HomeFragment: the widget was not allowed, releasing its id")
        }
    }

    // A fixed container whose content is swapped when the user changes design. Swapping the
    // fragment instead restores the old design's saved view state into the new one, whose views
    // share ids but not types, and that crashes.
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(inflater.context).also { inflateHomeContent(it) }

    private fun inflateHomeContent(container: FrameLayout) {
        isClassicHome = App.provide(container.context).settings.useClassicHome
        cachedPalette = null
        container.removeAllViews()
        LayoutInflater.from(container.context)
            .inflate(if (isClassicHome) R.layout.fragment_home_classic else R.layout.fragment_home, container, true)
        selfModeTile = container.findViewById(R.id.self_mode_button)
        usbTile = container.findViewById(R.id.usb_button)
        settingsTile = container.findViewById(R.id.settings_button)
        wifiTile = container.findViewById(R.id.wifi_button)
        exitButton = container.findViewById(R.id.exit_button)
        appsButton = container.findViewById(R.id.apps_button)
        primaryAction = container.findViewById(R.id.home_primary_action)
        primaryCancel = container.findViewById(R.id.home_primary_cancel)
        favoriteSlots = HomeUiHelper.favoriteSlotIds.mapNotNull { container.findViewById(it) }
        setupListeners()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        renderHome()

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                commManager.connectionState.collect { renderHome() }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AapService.scanningState.collect { renderHome() }
            }
        }

        mainViewModel.usbDevices.observe(viewLifecycleOwner) { renderHome() }

        val appSettings = App.provide(requireContext()).settings

        if (appSettings.autoStartOnScreenOn || appSettings.autoStartOnBoot) {
            ContextCompat.startForegroundService(requireContext(),
                Intent(requireContext(), AapService::class.java))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val isAutoConnectEnabled = appSettings.autoStartSelfMode ||
                appSettings.autoConnectLastSession ||
                appSettings.autoConnectSingleUsbDevice

            val delaySec = appSettings.autoConnectDelaySeconds
            if (isAutoConnectEnabled && delaySec > 0 && !forceSelfModeLaunch && !commManager.isConnected) {
                AppLog.i("HomeFragment: Waiting ${delaySec}s before attempting auto-connect...")
                countDownToAutoConnect(delaySec, autoConnectTarget(appSettings))
            }

            if (!isAdded || commManager.isConnected) return@launch
            if (autoConnectCancelled) {
                AppLog.i("HomeFragment: auto-connect cancelled from the home screen countdown")
                return@launch
            }

            for (methodId in appSettings.autoConnectPriorityOrder) {
                if (commManager.isConnected) break
                when (methodId) {
                    Settings.AUTO_CONNECT_LAST_SESSION -> {
                        if (appSettings.autoConnectLastSession && !hasAttemptedAutoConnect && !commManager.isConnected) {
                            hasAttemptedAutoConnect = true
                            if (attemptAutoConnect()) {
                                (requireActivity() as? MainActivity)?.beginAutoConnect(
                                    "auto-connect last session",
                                    MainActivity.ConnectionUiMode.OVERLAY
                                )
                            }
                        }
                    }
                    Settings.AUTO_CONNECT_SELF_MODE -> {
                        if ((appSettings.autoStartSelfMode || forceSelfModeLaunch) && !hasAutoStarted && !commManager.isConnected) {
                            hasAutoStarted = true
                            forceSelfModeLaunch = false // Reset once processed
                            (requireActivity() as? MainActivity)?.beginAutoConnect(
                                "auto-start self mode",
                                MainActivity.ConnectionUiMode.OVERLAY
                            )
                            startSelfMode()
                        }
                    }
                    Settings.AUTO_CONNECT_SINGLE_USB -> {
                        if (appSettings.autoConnectSingleUsbDevice && !hasAttemptedSingleUsbAutoConnect && !commManager.isConnected) {
                            hasAttemptedSingleUsbAutoConnect = true
                            if (attemptSingleUsbAutoConnect()) {
                                (requireActivity() as? MainActivity)?.beginAutoConnect(
                                    "auto-connect single USB",
                                    MainActivity.ConnectionUiMode.OVERLAY
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * The auto-connect delay, shown on the main button so the driver can skip or cancel it. Nothing
     * is shown, and nothing waited for, when no method is left that would actually start.
     */
    private suspend fun countDownToAutoConnect(seconds: Int, target: HomePrimaryPolicy.Connection?) {
        if (target == null) return
        reconnectTarget = target
        reconnectCountdown = viewLifecycleOwner.lifecycleScope.launch {
            for (left in seconds downTo 1) {
                reconnectSecondsLeft = left
                renderHome()
                delay(1000L)
            }
        }
        reconnectCountdown?.join()
        reconnectCountdown = null
        reconnectTarget = null
        renderHome()
    }

    /** The connection the first auto-connect method still to run will try, in the user's priority order. */
    private fun autoConnectTarget(appSettings: Settings): HomePrimaryPolicy.Connection? {
        if (autoConnectCancelled) return null
        return appSettings.autoConnectPriorityOrder.firstNotNullOfOrNull { methodId ->
            when (methodId) {
                Settings.AUTO_CONNECT_LAST_SESSION ->
                    if (appSettings.autoConnectLastSession && !hasAttemptedAutoConnect) HomeUiHelper.lastConnection(appSettings) else null
                Settings.AUTO_CONNECT_SELF_MODE ->
                    if (appSettings.autoStartSelfMode && !hasAutoStarted) HomePrimaryPolicy.Connection.SELF else null
                Settings.AUTO_CONNECT_SINGLE_USB ->
                    if (appSettings.autoConnectSingleUsbDevice && !hasAttemptedSingleUsbAutoConnect) HomePrimaryPolicy.Connection.USB else null
                else -> null
            }
        }
    }

    private fun cancelAutoConnectCountdown() {
        autoConnectCancelled = true
        reconnectCountdown?.cancel()
    }

    private fun renderHome() {
        val ctx = context ?: return
        val root = view ?: return
        val appSettings = App.provide(ctx).settings
        if (isClassicHome) {
            renderClassicHome(ctx, root, appSettings)
            return
        }
        val usbPhone = detectedUsbPhone(ctx)
        val decision = HomePrimaryPolicy.decide(
            HomePrimaryPolicy.Input(
                chosen = HomeUiHelper.chosenConnections(appSettings),
                lastConnection = HomeUiHelper.lastConnection(appSettings),
                usbPhoneDetected = usbPhone != null,
                sessionActive = commManager.isConnected,
                reconnectTarget = reconnectTarget,
                wifiScanning = AapService.scanningState.value
            )
        )
        this.decision = decision
        val palette = homePalette(ctx)
        HomeUiHelper.applyBar(ctx, root, appSettings, decision.barConnections, palette)
        HomeUiHelper.applyPrimaryCard(
            ctx, root, appSettings, decision, usbPhone?.let { phoneName(it) }, reconnectSecondsLeft, palette
        )
        HomeUiHelper.fitDriverBar(root, appSettings.homeButtonScalePercent)
        renderHomeInfo()
    }

    /**
     * The date, the time and the speed, and nothing else: the ticker has to refresh this block
     * once a minute, not rebuild the whole home screen.
     */
    private fun renderHomeInfo() {
        val ctx = context ?: return
        val root = view ?: return
        if (isClassicHome) return
        HomeUiHelper.applyInfoBlock(
            rootView = root,
            settings = App.provide(ctx).settings,
            palette = homePalette(ctx),
            clock = clockText(),
            date = dateText(),
            speed = HomeUiHelper.speedText(gpsSpeedKmh),
            countdownVisible = decision?.status == HomePrimaryPolicy.Status.RECONNECTING
        )
    }

    // The format follows the unit's locale and its 12/24 hour setting, which is what the driver
    // already reads everywhere else on the screen.
    private fun clockText(): String =
        android.text.format.DateFormat.getTimeFormat(requireContext()).format(java.util.Date())

    private fun dateText(): String =
        android.text.format.DateFormat.getMediumDateFormat(requireContext()).format(java.util.Date())

    private fun startHomeInfo() {
        val appSettings = App.provide(requireContext()).settings
        homeInfoTicker?.cancel()
        homeInfoTicker = null
        stopSpeedUpdates()
        gpsSpeedKmh = null
        if (!appSettings.showHomeClock && !appSettings.showHomeSpeed) return
        if (appSettings.showHomeSpeed) {
            // Whatever the unit already knows, so the readout is not blank while the chip locks.
            gpsSpeedKmh = LocationHolder.currentGpsFix(requireContext(), SPEED_MAX_AGE_MS)
                ?.takeIf { it.hasSpeed() }
                ?.let { kmh(it.speed) }
            startSpeedUpdates()
        }
        renderHomeInfo()
        if (appSettings.showHomeClock) {
            homeInfoTicker = viewLifecycleOwner.lifecycleScope.launch {
                while (isActive) {
                    val now = System.currentTimeMillis()
                    renderHomeInfo()
                    // To the next minute, so the clock turns over with the unit's own.
                    delay(MINUTE_MS - now % MINUTE_MS + CLOCK_SLACK_MS)
                }
            }
        }
    }

    private fun stopHomeInfo() {
        homeInfoTicker?.cancel()
        homeInfoTicker = null
        stopSpeedUpdates()
        gpsSpeedKmh = null
    }

    /**
     * The home screen is what the driver looks at while nothing is projected, and the app's own
     * GPS service only runs with a session, so the readout subscribes from here and lets go in
     * onPause.
     */
    @SuppressLint("MissingPermission")
    private fun startSpeedUpdates() {
        if (gpsListener != null) return
        val ctx = context ?: return
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            AppLog.i("HomeFragment: the speed readout has no location permission, showing the placeholder")
            return
        }
        val manager = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                gpsSpeedKmh = if (location.hasSpeed()) kmh(location.speed) else null
                renderHomeInfo()
            }

            override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {}

            override fun onProviderEnabled(provider: String) {}

            override fun onProviderDisabled(provider: String) {}
        }
        try {
            // Time only, no distance gate: a head unit sitting still still reports its speed, and
            // this is the same subscription the app's own GPS service makes.
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, SPEED_INTERVAL_MS, 0f, listener, Looper.getMainLooper()
            )
            gpsListener = listener
        } catch (e: Exception) {
            // A unit with no GPS provider, or one that refuses the subscription.
            AppLog.w("HomeFragment: the speed readout could not subscribe to GPS: ${e.message}")
        }
    }

    private fun stopSpeedUpdates() {
        val listener = gpsListener ?: return
        gpsListener = null
        (context?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.removeUpdates(listener)
    }

    private fun kmh(metersPerSecond: Float): Int = (metersPerSecond * 3.6f).roundToInt()

    private fun renderClassicHome(ctx: Context, root: View, appSettings: Settings) {
        val isConnected = commManager.isConnected
        ClassicHomeUiHelper.applyConnectionState(ctx, root, isConnected, AapService.scanningState.value)
        ClassicHomeUiHelper.applyButtonVisibility(root, appSettings, isConnected)
        ClassicHomeUiHelper.applyButtonStyles(ctx, root, appSettings, isNightActive(), isConnected)
    }

    private fun detectedUsbPhone(ctx: Context): UsbDevice? =
        mainViewModel.usbDevices.value.orEmpty().map { it.wrappedDevice }
            .firstOrNull { UsbDeviceCompat.isConnectable(ctx, it) }

    // The product name is API 21; older units say "Phone detected" instead.
    private fun phoneName(device: UsbDevice): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) device.productName?.takeIf { it.isNotBlank() } else null

    private fun isNightActive(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    // A theme change recreates the activity, so the palette can be kept for the life of the view.
    private fun homePalette(ctx: Context): HomeUiHelper.Palette = cachedPalette ?: run {
        val appSettings = App.provide(ctx).settings
        val mode = HomeThemePolicy.resolve(appSettings.appTheme, appSettings.useExtremeDarkMode, isNightActive())
        HomeUiHelper.palette(ctx, mode).also { cachedPalette = it }
    }

    private fun updateLauncherViews() {
        val ctx = context ?: return
        val root = view ?: return
        val appSettings = App.provide(ctx).settings
        val isLauncherActive = appSettings.isCarLauncherActive
        val shouldShowExit = CarLauncherManager.shouldShowExitButton(
            isCarLauncherEnabled = appSettings.enableCarLauncher,
            isDefaultLauncher = CarLauncherManager.isDefaultLauncher(ctx)
        )
        exitButton.visibility = if (shouldShowExit) View.VISIBLE else View.GONE
        if (isClassicHome) {
            appsButton.visibility = if (isLauncherActive) View.VISIBLE else View.GONE
        } else {
            HomeUiHelper.applyFavoritesVisibility(root, isLauncherActive, appSettings.showHomeFavoriteApps)
        }
        if (isLauncherActive) {
            AppDrawerFragment.preload(ctx)
            if (!isClassicHome && appSettings.showHomeFavoriteApps) loadFavorites()
        }
        if (!isClassicHome) {
            applyCompactHome(root, appSettings, isLauncherActive)
            // The column's width is only known after a layout, and the cap changes that width.
            root.post { if (isAdded && view === root) applyContentWidth(root) }
            renderWidgets()
        }
    }

    /**
     * On a small or narrow screen used as the launcher, the main button's card is the part that
     * makes everything look cramped: it is hidden and the favorite apps and widgets take the space
     * instead. The connection buttons stay in the driver bar, which already starts each connection
     * directly, and the date and the speed keep a thin strip at the top.
     */
    private fun applyCompactHome(root: View, appSettings: Settings, isLauncherActive: Boolean) {
        val config = resources.configuration
        val small = config.screenHeightDp < COMPACT_HEIGHT_DP || config.screenWidthDp < COMPACT_WIDTH_DP
        val compact = isLauncherActive && small
        compactHome = compact
        val card = root.findViewById<View>(R.id.home_primary_card) ?: return
        val content = root.findViewById<View>(R.id.home_primary_content) ?: return
        val area = root.findViewById<View>(R.id.home_favorites_area) ?: return

        content.visibility = if (compact) View.GONE else View.VISIBLE
        val cardShown = !compact || appSettings.showHomeClock || appSettings.showHomeSpeed
        card.visibility = if (cardShown) View.VISIBLE else View.GONE

        val cardParams = card.layoutParams as? LinearLayout.LayoutParams ?: return
        val cardHeight = if (compact) ViewGroup.LayoutParams.WRAP_CONTENT else 0
        val cardWeight = if (compact) 0f else 1f
        if (cardParams.height != cardHeight || cardParams.weight != cardWeight) {
            cardParams.height = cardHeight
            cardParams.weight = cardWeight
            card.layoutParams = cardParams
        }

        val areaParams = area.layoutParams as? LinearLayout.LayoutParams ?: return
        val areaHeight = if (compact) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
        val areaWeight = if (compact) 1f else 0f
        if (areaParams.height != areaHeight || areaParams.weight != areaWeight) {
            areaParams.height = areaHeight
            areaParams.weight = areaWeight
            area.layoutParams = areaParams
        }
    }

    /**
     * On an ultrawide unit the card did not need to run edge to edge: a panel that wide puts the
     * text on one end and the time on the other. Above [R.dimen.home_content_max_width] the card
     * and the favorite apps keep that width and sit centered in the column.
     */
    private fun applyContentWidth(root: View) {
        if (resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) return
        val column = root.findViewById<View>(R.id.home_primary_card)?.parent as? View ?: return
        val available = column.width - column.paddingLeft - column.paddingRight
        if (available <= 0) return
        val cap = resources.getDimensionPixelSize(R.dimen.home_content_max_width)
        val width = minOf(available, cap)
        listOf(R.id.home_primary_card, R.id.home_favorites_area).forEach { id ->
            val view = root.findViewById<View>(id) ?: return@forEach
            val params = view.layoutParams as? LinearLayout.LayoutParams ?: return@forEach
            if (params.width == width && params.gravity == Gravity.CENTER_HORIZONTAL) return@forEach
            params.width = width
            params.gravity = Gravity.CENTER_HORIZONTAL
            view.layoutParams = params
        }
    }

    private class FavoriteApp(val label: CharSequence, val icon: Drawable)

    /** Loads only the four favorites, off the main thread: the drawer's full list is not needed here. */
    private fun loadFavorites() {
        val ctx = requireContext().applicationContext
        val pm = ctx.packageManager
        val appSettings = App.provide(requireContext()).settings
        val targets = appSettings.homeFavoriteApps
        viewLifecycleOwner.lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { targets.map { loadFavorite(ctx, pm, it) } }
            apps.forEachIndexed { slot, app -> bindFavorite(favoriteSlots[slot], app) }
            // An uninstalled app, or a shortcut the app dropped, frees its slot so "Add to
            // favorites" fills the "+" the user sees.
            val gone = targets.indices.filter { targets[it] != FavoriteTarget.Empty && apps[it] == null }
            if (gone.isNotEmpty()) {
                appSettings.homeFavoriteApps = gone.fold(targets) { slots, slot -> FavoriteAppsPolicy.remove(slots, slot) }
            }
        }
    }

    private fun loadFavorite(context: Context, pm: PackageManager, target: FavoriteTarget): FavoriteApp? =
        when (target) {
            is FavoriteTarget.Empty -> null
            is FavoriteTarget.App -> appFavorite(pm, target.packageName)
            is FavoriteTarget.Shortcut -> shortcutFavorite(context, pm, target)
        }

    private fun appFavorite(pm: PackageManager, packageName: String): FavoriteApp? = try {
        val info = pm.getApplicationInfo(packageName, 0)
        FavoriteApp(pm.getApplicationLabel(info), pm.getApplicationIcon(info))
    } catch (e: PackageManager.NameNotFoundException) {
        AppLog.i("HomeFragment: favorite $packageName is no longer installed, showing its slot as free")
        null
    }

    /** A shortcut the app still offers, with its own icon and the app's as the fallback. */
    private fun shortcutFavorite(context: Context, pm: PackageManager, target: FavoriteTarget.Shortcut): FavoriteApp? {
        val shortcut = LauncherShortcuts.forPackage(context, target.packageName)
            .firstOrNull { it.shortcutId == target.shortcutId }
        if (shortcut == null) {
            AppLog.i("HomeFragment: favorite shortcut ${target.packageName}/${target.shortcutId} is gone, showing its slot as free")
            return null
        }
        val icon = LauncherShortcuts.icon(context, target.packageName, target.shortcutId)
            ?: appFavorite(pm, target.packageName)?.icon
        if (icon == null) return null
        return FavoriteApp(shortcut.label, icon)
    }

    private fun bindFavorite(slotView: View, app: FavoriteApp?) {
        val icon = slotView.findViewById<ImageView>(R.id.favorite_icon)
        val label = slotView.findViewById<TextView>(R.id.favorite_label)
        if (app == null) {
            icon.setImageResource(R.drawable.ic_add_white)
            label.setText(R.string.home_add_favorite)
        } else {
            icon.setImageDrawable(app.icon)
            label.text = app.label
        }
        (slotView as? ViewGroup)?.let { HomeUiHelper.paintFavoriteSlot(slotView.context, it, app != null, homePalette(slotView.context)) }
    }

    /**
     * The widgets under the favorite apps, rebuilt rather than updated: there are at most two, and
     * a rebuild is what tells a widget that was just added from one that was already there.
     */
    private fun renderWidgets() {
        val ctx = context ?: return
        val root = view ?: return
        if (isClassicHome) return
        val scroll = root.findViewById<HorizontalScrollView>(R.id.home_widgets_scroll) ?: return
        val row = root.findViewById<LinearLayout>(R.id.home_widgets_row) ?: return
        row.removeAllViews()
        val appSettings = App.provide(ctx).settings
        if (!appSettings.isCarLauncherActive) {
            scroll.visibility = View.GONE
            return
        }

        val ids = pruneWidgets(ctx, appSettings.homeWidgets)
        val palette = homePalette(ctx)
        ids.forEach { appWidgetId ->
            widgetTile(ctx, row, appWidgetId, palette)?.let { row.addView(it) }
        }
        // Android 5.0 is where the system lets an app ask for a widget at all.
        if (HomeWidgets.supported() && ids.size < HomeWidgetsPolicy.MAX_WIDGETS) {
            val addTile = LayoutInflater.from(ctx).inflate(R.layout.item_home_widget_add, row, false)
            addTile.setOnClickListener { pickWidget() }
            HomeUiHelper.paintWidgetAddTile(ctx, addTile as ViewGroup, palette)
            row.addView(addTile)
        }
        if (row.childCount == 0) {
            scroll.visibility = View.GONE
            return
        }
        scroll.visibility = View.VISIBLE
        val addTileHeight = ctx.resources.getDimensionPixelSize(R.dimen.home_favorites_height)
        val tallest = ids.maxOfOrNull { HomeWidgets.minHeightPx(ctx, it) } ?: 0
        // One frame later: changing the row's height or hiding it inside the layout pass does not
        // take, and the main button is left with the space the row was not going to use.
        root.post {
            if (!isAdded || view !== root) return@post
            fitWidgetStrip(root, maxOf(tallest, addTileHeight))
        }
    }

    /** @return the frame that holds the widget, or null when its provider cannot draw any more. */
    private fun widgetTile(
        ctx: Context,
        parent: ViewGroup,
        appWidgetId: Int,
        palette: HomeUiHelper.Palette
    ): View? {
        val hosted = HomeWidgets.view(ctx, appWidgetId) ?: return null
        val frame = LayoutInflater.from(ctx).inflate(R.layout.item_home_widget, parent, false)
        (frame as ViewGroup).addView(
            hosted,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        HomeUiHelper.paintWidgetSlot(ctx, frame, palette)
        // The width the widget asks for is the smallest it draws at. The row scrolls rather than
        // squeezing it, and a narrow widget still gets a tile sized square. On the compact home the
        // row is the whole width, so the widget gives up the little the "+" needs and reflows.
        val gap = ctx.resources.getDimensionPixelSize(R.dimen.home_gap)
        val addTileWidth = ctx.resources.getDimensionPixelSize(R.dimen.home_favorites_height)
        val ceiling = if (compactHome) {
            ctx.resources.displayMetrics.widthPixels - gap * 3 - addTileWidth
        } else {
            Int.MAX_VALUE
        }
        val width = minOf(HomeWidgets.minWidthPx(ctx, appWidgetId), ceiling)
            .coerceAtLeast(addTileWidth)
        frame.layoutParams = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT)
        frame.setOnLongClickListener { showWidgetMenu(appWidgetId, it) }
        return frame
    }

    /**
     * Gives the widget row what the main button can spare and no more. Without this a tall widget
     * pushed the main button's action off the bottom of a short screen, the same way the driver
     * bar used to lose its Exit tile.
     */
    private fun fitWidgetStrip(root: View, preferredPx: Int) {
        val ctx = root.context
        val scroll = root.findViewById<View>(R.id.home_widgets_scroll) ?: return
        val area = root.findViewById<View>(R.id.home_favorites_area) ?: return
        val appsRow = root.findViewById<View>(R.id.home_favorites) ?: return
        val card = root.findViewById<View>(R.id.home_primary_card) ?: return
        val column = area.parent as? View ?: return
        val gap = ctx.resources.getDimensionPixelSize(R.dimen.home_gap)

        // A portrait home stacks the driver bar in this same column, so its height is not room.
        val bar = root.findViewById<View>(R.id.home_driver_bar)
        val below = if (bar != null && bar.parent === column && bar.visibility == View.VISIBLE) {
            bar.height + gap
        } else 0
        val columnHeight = column.height - column.paddingTop - column.paddingBottom - below
        val cardMin = cardMinimumHeight(card)
        val room = WidgetStripPolicy.roomFor(columnHeight, cardMin, appsRow.height, gap)
        val content = card.findViewById<View>(R.id.home_primary_content)
        val compact = content != null && content.visibility != View.VISIBLE
        val minPx = ctx.resources.getDimensionPixelSize(R.dimen.home_widget_strip_min)
        val height = if (compact) {
            // No main button to protect here: the widgets take everything the favorite apps row
            // leaves, so the compact home is not a small strip with a lot of empty screen under it.
            (columnHeight - cardMin - gap * 2 - appsRow.height).coerceAtLeast(minPx)
        } else {
            WidgetStripPolicy.height(
                roomPx = room,
                minPx = minPx,
                maxPx = ctx.resources.getDimensionPixelSize(R.dimen.home_widget_strip_max),
                tallestWidgetPx = preferredPx
            )
        }
        if (height <= 0) {
            AppLog.i(
                "HomeFragment: no room for the widget row beside the main button " +
                    "(room=${room}px, card needs ${cardMin}px), leaving it out"
            )
            scroll.visibility = View.GONE
            return
        }
        AppLog.i("HomeFragment: the widget row is ${height}px tall, out of ${room}px of room")
        if (scroll.height != height) {
            scroll.layoutParams = (scroll.layoutParams ?: return).also { it.height = height }
            scroll.requestLayout()
        }
        tellWidgetsTheirSize(root, height)
    }

    /**
     * The height the main button cannot give up: its content measured with no height limit, so the
     * answer does not depend on how much room the card happens to have right now. Measuring the
     * children as they currently sit was the bug: once the card was squeezed they reported the
     * squeezed heights, the arithmetic believed it, and it kept the squeeze.
     */
    private fun cardMinimumHeight(card: View): Int {
        val content = card.findViewById<View>(R.id.home_primary_content) ?: return 0
        if (content.visibility != View.VISIBLE) {
            // Compact home: the card is only the strip that holds the date and the speed.
            val info = card.findViewById<View>(R.id.home_info) ?: return 0
            if (card.visibility != View.VISIBLE || info.visibility != View.VISIBLE) return 0
            val width = card.width - 2 * card.resources.getDimensionPixelSize(R.dimen.home_card_padding_h)
            if (width <= 0) return 0
            info.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val params = info.layoutParams as? FrameLayout.LayoutParams
            return info.measuredHeight + (params?.topMargin ?: 0)
        }
        val width = if (content.width > 0) content.width else card.width
        if (width <= 0) return 0
        content.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return content.measuredHeight
    }

    /** Tells every hosted widget the box it ended up with, so one that reflows lays itself out. */
    private fun tellWidgetsTheirSize(root: View, height: Int) {
        val row = root.findViewById<ViewGroup>(R.id.home_widgets_row) ?: return
        val padding = root.resources.getDimensionPixelSize(R.dimen.home_widget_padding)
        // A scroll view measures its child with AT_MOST, so a row that asks for match_parent does
        // not get the viewport's height. Every box is told its size instead.
        setViewHeight(row, height)
        for (i in 0 until row.childCount) {
            val frame = row.getChildAt(i) as? ViewGroup ?: continue
            setViewHeight(frame, height)
            val hosted = frame.getChildAt(0) as? AppWidgetHostView ?: continue
            HomeWidgets.tellSize(hosted, (frame.layoutParams?.width ?: 0) - padding * 2, height - padding * 2)
        }
        // The "+" keeps a tile shape: on the compact home the row is tall, and a stretched dashed
        // rectangle with its label in the middle reads as a mistake.
        row.findViewById<View>(R.id.widget_add_tile)?.let { add ->
            if (add.width <= 0) return@let
            setViewHeight(add, minOf(height, add.width * 3 / 2))
        }
    }

    private fun setViewHeight(view: View, height: Int) {
        val params = view.layoutParams ?: return
        if (params.height == height) return
        params.height = height
        view.layoutParams = params
    }

    /** Drops the widgets whose provider is gone, so an uninstalled widget frees its place. */
    private fun pruneWidgets(ctx: Context, stored: List<Int>): List<Int> {
        val alive = stored.filter { appWidgetId ->
            val isAlive = HomeWidgets.isAlive(ctx, appWidgetId)
            if (!isAlive) {
                AppLog.i("HomeFragment: widget $appWidgetId is no longer installed, dropping it")
                HomeWidgets.forget(ctx, appWidgetId)
            }
            isAlive
        }
        if (alive.size != stored.size) App.provide(ctx).settings.homeWidgets = alive
        return alive
    }

    private fun showWidgetMenu(appWidgetId: Int, anchor: View): Boolean {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(R.string.remove).setOnMenuItemClickListener {
                removeWidget(appWidgetId)
                true
            }
            show()
        }
        return true
    }

    private fun removeWidget(appWidgetId: Int) {
        val ctx = requireContext()
        val appSettings = App.provide(ctx).settings
        appSettings.homeWidgets = HomeWidgetsPolicy.remove(appSettings.homeWidgets, appWidgetId)
        HomeWidgets.forget(ctx, appWidgetId)
        renderWidgets()
    }

    /** The list of widgets the unit has, named the way their own apps name them. */
    private fun pickWidget() {
        val ctx = requireContext()
        val providers = HomeWidgets.providers(ctx)
        if (providers.isEmpty()) {
            ToastUtils.showToast(ctx, R.string.home_widgets_none, Toast.LENGTH_LONG, force = true)
            return
        }
        val adapter = object : ArrayAdapter<HomeWidgets.Provider>(
            ctx, R.layout.item_widget_option, providers
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView
                    ?: LayoutInflater.from(context).inflate(R.layout.item_widget_option, parent, false)
                val provider = providers[position]
                view.findViewById<TextView>(R.id.widget_option_label).text = provider.label
                view.findViewById<TextView>(R.id.widget_option_app).text = provider.appLabel
                view.findViewById<ImageView>(R.id.widget_option_icon)?.setImageDrawable(provider.icon)
                return view
            }
        }
        MaterialAlertDialogBuilder(ctx, R.style.DarkAlertDialog)
            .setTitle(R.string.home_widget_pick_title)
            .setAdapter(adapter) { _, which -> addWidget(providers[which].info.provider) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Asks the system for the widget the user picked. From Android 5.0 the app may not bind one on
     * its own: when the system says no, it is the user who is asked, and the id waits for them.
     */
    private fun addWidget(provider: ComponentName) {
        val ctx = requireContext()
        val appWidgetId = HomeWidgets.allocateId(ctx)
        if (HomeWidgets.tryBind(ctx, appWidgetId, provider)) {
            placeWidget(appWidgetId)
            return
        }
        pendingWidgetId = appWidgetId
        widgetBindLauncher.launch(HomeWidgets.bindRequest(appWidgetId, provider))
    }

    private fun placeWidget(appWidgetId: Int) {
        val ctx = requireContext()
        val appSettings = App.provide(ctx).settings
        val updated = HomeWidgetsPolicy.add(appSettings.homeWidgets, appWidgetId)
        if (updated == null) {
            HomeWidgets.forget(ctx, appWidgetId)
            ToastUtils.showToast(ctx, R.string.home_widgets_full, Toast.LENGTH_LONG, force = true)
            return
        }
        appSettings.homeWidgets = updated
        renderWidgets()
    }

    private fun onFavoriteClicked(slot: Int) {
        val target = App.provide(requireContext()).settings.homeFavoriteApps[slot]
        val launched = when (target) {
            is FavoriteTarget.Empty -> false
            is FavoriteTarget.App -> AppDrawerFragment.launchPackage(requireContext(), target.packageName)
            is FavoriteTarget.Shortcut ->
                LauncherShortcuts.launch(requireContext(), target.packageName, target.shortcutId)
        }
        if (!launched) openAppDrawer(favoriteSlot = slot)
    }

    private fun showFavoriteMenu(slot: Int, anchor: View): Boolean {
        val appSettings = App.provide(requireContext()).settings
        if (appSettings.homeFavoriteApps[slot] == FavoriteTarget.Empty) return false
        PopupMenu(requireContext(), anchor).apply {
            menu.add(R.string.home_favorite_change).setOnMenuItemClickListener {
                openAppDrawer(favoriteSlot = slot)
                true
            }
            menu.add(R.string.remove).setOnMenuItemClickListener {
                appSettings.homeFavoriteApps = FavoriteAppsPolicy.remove(appSettings.homeFavoriteApps, slot)
                loadFavorites()
                true
            }
            show()
        }
        return true
    }

    private fun openAppDrawer(favoriteSlot: Int? = null) {
        val controller = findNavController()
        if (controller.currentDestination?.id == R.id.homeFragment) {
            controller.navigate(
                R.id.action_homeFragment_to_appDrawerFragment,
                favoriteSlot?.let { AppDrawerFragment.pickFavoriteArgs(it) }
            )
        }
    }

    private fun openProjection() {
        val aapIntent = Intent(requireContext(), AapProjectionActivity::class.java)
        aapIntent.putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
        startActivity(aapIntent)
    }

    private fun startSelfModeInternal() {
        val intent = Intent(requireContext(), AapService::class.java)
        intent.action = AapService.ACTION_START_SELF_MODE
        ContextCompat.startForegroundService(requireContext(), intent)
        AppLog.i("Auto start selfmode")
    }

    private fun startSelfMode() {
        val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            connectivityManager.activeNetwork
        } else null

        val path = SelfLauncherManager.installedPath(requireContext())
        val offline = activeNetwork == null

        if (SelfLaunchRoutePolicy.needsDummyVpn(path, offline, VpnControl.isVpnAvailable())) {
            AppLog.i("Device is offline. Preparing Dummy VPN for Self Mode.")
            val vpnIntent = VpnControl.consentIntent(requireContext())
            if (vpnIntent != null) {
                vpnPermissionLauncher.launch(vpnIntent)
                return
            } else {
                AppLog.i("VPN permission already granted. Starting VPN service.")
                VpnControl.startVpn(requireContext());
            }
        } else if (offline) {
            if (path == SelfLaunchPath.HEADUNIT_SERVER) {
                AppLog.i("HomeFragment: Device is offline; Android Auto 17.4+ connects over 127.0.0.1:5277, so no dummy VPN.")
            } else {
                AppLog.i("Device is offline and VPN is not available in this build. Self Mode may fail.")
            }
        }
        startSelfModeInternal()
    }

    /**
     * Tries to start an auto-reconnect to the last session.
     *
     * @return `true` if a connection attempt was actually dispatched (so the
     *   caller should surface the pill), `false` if nothing was started (e.g.
     *   Native AA mode, no last session, missing USB device or permission).
     *   Returning a flag prevents the pill from being shown for 30 s when no
     *   work was queued.
     */
    private fun attemptAutoConnect(): Boolean {
        val ctx = context ?: return false
        val appSettings = App.provide(ctx).settings

        // [FIX] Skip manual WiFi connection if Native AA is selected.
        // Native AA handles its own handshake via Bluetooth/P2P.
        if (appSettings.wifiConnectionMode == WifiLauncherMode.NATIVE) {
            AppLog.i("HomeFragment: Native AA mode active. Skipping manual auto-connect attempt.")
            return false
        }

        if (!appSettings.autoConnectLastSession ||
            !appSettings.hasAcceptedDisclaimer ||
            commManager.isConnected) {
            return false
        }

        val connectionType = appSettings.lastConnectionType
        if (connectionType.isEmpty()) {
            AppLog.i("Auto-connect: No last session to reconnect to")
            return false
        }

        return when (connectionType) {
            Settings.CONNECTION_TYPE_WIFI -> {
                if (appSettings.wifiConnectionMode == WifiLauncherMode.AUTO) {
                    val ip = appSettings.lastConnectionIp
                    if (ip.isNotEmpty()) {
                        AppLog.i("Auto-connect: Attempting WiFi connection to $ip")
                        ToastUtils.showToast(ctx, getString(R.string.auto_connecting_to, ip), Toast.LENGTH_SHORT)
                        lifecycleScope.launch(Dispatchers.IO) { App.provide(ctx).commManager.connect(ip, 5277) }
                        ContextCompat.startForegroundService(ctx, Intent(ctx, AapService::class.java).apply {
                            action = AapService.ACTION_CONNECT_SOCKET
                        })
                        true
                    } else false
                } else {
                    AppLog.i("Auto-connect: Last session was WiFi, but connection mode is not Headunit Server. Skipping active connect.")
                    false
                }
            }
            Settings.CONNECTION_TYPE_USB -> {
                val lastUsbDevice = appSettings.lastConnectionUsbDevice
                if (lastUsbDevice.isNotEmpty()) {
                    val usbManager = UsbDeviceCompat.usbManager(requireContext())
                    val matchingDevice = usbManager?.deviceList?.values?.find { device ->
                        UsbDeviceCompat.getUniqueName(device) == lastUsbDevice
                    }
                    if (usbManager != null && matchingDevice != null && usbManager.hasPermission(matchingDevice)) {
                        AppLog.i("Auto-connect: Attempting USB connection to $lastUsbDevice")
                        ToastUtils.showToast(requireContext(), getString(R.string.auto_connecting_usb), Toast.LENGTH_SHORT)
                        ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                            action = AapService.ACTION_CHECK_USB
                        })
                        true
                    } else {
                        AppLog.i("Auto-connect: USB device $lastUsbDevice not found or no permission")
                        false
                    }
                } else false
            }
            Settings.CONNECTION_TYPE_NEARBY -> {
                AppLog.i("Auto-connect: Last session was via Google Nearby. AapService will handle discovery.")
                // No manual connect(ip) needed, NearbyManager in AapService manages this automatically on start.
                true
            }
            else -> false
        }
    }

    /**
     * @return `true` if a single-USB connection attempt was dispatched,
     *   `false` if guards (setting disabled, disclaimer pending, already
     *   connected) blocked it. Same intent as [attemptAutoConnect].
     */
    private fun attemptSingleUsbAutoConnect(): Boolean {
        val ctx = context ?: return false
        val appSettings = App.provide(ctx).settings
        if (!appSettings.autoConnectSingleUsbDevice ||
            !appSettings.hasAcceptedDisclaimer ||
            commManager.isConnected) return false

        AppLog.i("HomeFragment: Requesting single-USB auto-connect via AapService")
        ContextCompat.startForegroundService(ctx,
            Intent(ctx, AapService::class.java).apply {
                action = AapService.ACTION_CHECK_USB
            })
        return true
    }

    private fun setupListeners() {
        appsButton.setOnClickListener { openAppDrawer() }
        exitButton.setOnClickListener { exitApp() }
        selfModeTile.setOnClickListener { onSelfModeClicked() }
        usbTile.setOnClickListener { onUsbClicked() }
        wifiTile.setOnClickListener { onWifiClicked() }
        wifiTile.setOnLongClickListener {
            openNetworkList()
            true
        }
        settingsTile.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
        primaryAction?.setOnClickListener { onPrimaryActionClicked() }
        primaryCancel?.setOnClickListener { cancelAutoConnectCountdown() }
        favoriteSlots.forEachIndexed { slot, slotView ->
            slotView.setOnClickListener { onFavoriteClicked(slot) }
            slotView.setOnLongClickListener { showFavoriteMenu(slot, it) }
        }
    }

    private fun onPrimaryActionClicked() {
        val decision = decision ?: return
        when (decision.status) {
            HomePrimaryPolicy.Status.ACTIVE_SESSION -> openProjection()
            // Ends the wait; the auto-connect that was counting down starts right away.
            HomePrimaryPolicy.Status.RECONNECTING -> reconnectCountdown?.cancel()
            else -> when (decision.primary) {
                HomePrimaryPolicy.Connection.USB -> onUsbClicked()
                HomePrimaryPolicy.Connection.WIFI -> onWifiClicked()
                HomePrimaryPolicy.Connection.SELF -> onSelfModeClicked()
            }
        }
    }

    private fun exitApp() {
        val appSettings = App.provide(requireContext()).settings
        val keepServiceAlive = appSettings.autoStartOnBoot ||
            appSettings.autoStartOnScreenOn ||
            (appSettings.autoStartOnUsb && appSettings.reopenOnReconnection)
        if (keepServiceAlive) {
            val disconnectIntent = Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_DISCONNECT
            }
            ContextCompat.startForegroundService(requireContext(), disconnectIntent)
        } else {
            val stopServiceIntent = Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_STOP_SERVICE
            }
            ContextCompat.startForegroundService(requireContext(), stopServiceIntent)
        }
        requireActivity().finishAffinity()
    }

    private fun onSelfModeClicked() {
        if (commManager.isConnected) {
            openProjection()
        } else {
            if (!AppPermissions.isOverlayGranted(requireContext())) {
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.overlay_permission_title)
                    .setMessage(R.string.self_mode_overlay_permission_description)
                    .setPositiveButton(R.string.open_settings) { _, _ ->
                        val intent = Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:${requireContext().packageName}")
                        )
                        startActivity(intent)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } else {
                (requireActivity() as? MainActivity)?.beginAutoConnect(
                    "manual self mode",
                    MainActivity.ConnectionUiMode.OVERLAY
                )
                startSelfMode()
            }
        }
    }

    private fun onUsbClicked() {
        // Already connected to Android Auto - just show projection
        if (commManager.isConnected) {
            openProjection()
            return
        }

        AapService.instance?.liftUsbCancel("the USB button was pressed")

        // Get list of Android USB devices
        val usbManager = UsbDeviceCompat.usbManager(requireContext())
        val androidDevices = if (usbManager == null) emptyList() else {
            UsbDeviceDiagnostics.logDeviceList(requireContext(), usbManager, "USB button")
            usbManager.deviceList.values.filter { UsbDeviceCompat.isConnectable(requireContext(), it) }
        }

        // If exactly one device found - auto-connect
        if (usbManager != null && androidDevices.size == 1) {
            val device = UsbDeviceCompat(androidDevices[0])
            AppLog.i("USB button: Single device found - ${device.uniqueName}, auto-connecting")
            (requireActivity() as? MainActivity)?.beginAutoConnect(
                "USB button auto-connect",
                MainActivity.ConnectionUiMode.OVERLAY
            )

            if (device.isInAccessoryMode) {
                ContextCompat.startForegroundService(requireContext(),
                    Intent(requireContext(), AapService::class.java).apply {
                        action = AapService.ACTION_CHECK_USB
                    })
            } else {
                if (usbManager.hasPermission(device.wrappedDevice)) {
                    val usbMode = UsbAccessoryMode(usbManager)
                    val useLibusb = App.provide(requireContext()).settings.useLibusb
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        val success = usbMode.connectAndSwitch(device.wrappedDevice, useLibusb)
                        withContext(Dispatchers.Main) {
                            context?.let { ctx ->
                                if (success) {
                                    ToastUtils.showToast(ctx, R.string.switching_to_android_auto, Toast.LENGTH_SHORT)
                                } else {
                                    ToastUtils.showToast(ctx, R.string.switch_failed, Toast.LENGTH_SHORT, force = true)
                                }
                            }
                        }
                    }
                } else {
                    ToastUtils.showToast(requireContext(), R.string.requesting_usb_permission, Toast.LENGTH_SHORT)
                    ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java))
                    usbManager.requestPermission(
                        device.wrappedDevice,
                        UsbReceiver.createPermissionPendingIntent(requireContext())
                    )
                }
            }
        } else {
            // 0 or multiple devices - open the list
            val controller = findNavController()
            if (controller.currentDestination?.id == R.id.homeFragment) {
                controller.navigate(R.id.action_homeFragment_to_usbListFragment)
            }
        }
    }

    private fun onWifiClicked() {
        val mode = App.provide(requireContext()).settings.wifiConnectionMode
        when (mode) {
            WifiLauncherMode.AUTO -> { // Auto (Headunit Server) - One-Shot Scan
                if (commManager.isConnected) {
                    // Already connected
                } else if (AapService.scanningState.value) {
                    ToastUtils.showToast(requireContext(), getString(R.string.already_scanning), Toast.LENGTH_SHORT)
                } else {
                    ToastUtils.showToast(requireContext(), getString(R.string.searching_headunit_server), Toast.LENGTH_SHORT)
                    (requireActivity() as? MainActivity)?.beginAutoConnect(
                        "manual WiFi headunit server scan",
                        MainActivity.ConnectionUiMode.OVERLAY
                    )
                    val intent = Intent(requireContext(), AapService::class.java).apply {
                        action = AapService.ACTION_START_WIRELESS_SCAN
                    }
                    ContextCompat.startForegroundService(requireContext(), intent)
                }
            }
            WifiLauncherMode.HELPER -> { // Helper (Wireless Launcher)
                if (commManager.isConnected) {
                    // Already connected
                } else {
                    val strategy = App.provide(requireContext()).settings.helperConnectionStrategy
                    if (strategy == HelperStrategy.HEADUNIT_HOTSPOT) {
                        if (!AapService.scanningState.value) {
                            (requireActivity() as? MainActivity)?.beginAutoConnect(
                                "manual WiFi helper scan",
                                MainActivity.ConnectionUiMode.OVERLAY
                            )
                            val intent = Intent(requireContext(), AapService::class.java).apply {
                                action = AapService.ACTION_START_WIRELESS_SCAN
                            }
                            ContextCompat.startForegroundService(requireContext(), intent)
                        }
                        com.andrerinas.openheadunit.utils.ShareHotspotQrDialog.show(
                            requireContext()
                        )
                    } else if (strategy == HelperStrategy.NEARBY_DEVICES) {
                        // Nearby Devices — show live discovery dialog
                        showNearbyDeviceSelector()
                    } else if (AapService.scanningState.value) {
                        ToastUtils.showToast(requireContext(), getString(R.string.already_searching_phone), Toast.LENGTH_SHORT)
                    } else {
                        ToastUtils.showToast(requireContext(), getString(R.string.searching_phone), Toast.LENGTH_SHORT)
                        (requireActivity() as? MainActivity)?.beginAutoConnect(
                            "manual WiFi helper scan",
                            MainActivity.ConnectionUiMode.OVERLAY
                        )
                        val intent = Intent(requireContext(), AapService::class.java).apply {
                            action = AapService.ACTION_START_WIRELESS_SCAN
                        }
                        ContextCompat.startForegroundService(requireContext(), intent)
                    }
                }
            }
            WifiLauncherMode.NATIVE -> { // Native AA
                // A unit whose Bluetooth is an external module has no Android radio to check
                // and no Android device to pick; AapService arms the module route instead.
                val route = NativeAaHandshakeManager.wifiButtonRoute(requireContext())
                if (route == ExternalBtTransportPolicy.WifiButton.MODULE) {
                    ToastUtils.showToast(requireContext(), getString(R.string.searching_phone), Toast.LENGTH_SHORT)
                    val intent = Intent(requireContext(), AapService::class.java).apply {
                        action = AapService.ACTION_NATIVE_AA_POKE
                    }
                    ContextCompat.startForegroundService(requireContext(), intent)
                } else if (route == ExternalBtTransportPolicy.WifiButton.REFUSED) {
                    AppLog.i(
                        "HomeFragment: WiFi button refused: route=$route, daemon answer=" +
                            "${ZbtDaemonReachability.cached()} ${ZbtDaemonReachability.answerAgeMs()?.let { it / 1000 }}s old"
                    )
                    ToastUtils.showToast(requireContext(), getString(R.string.native_aa_poke_not_running), Toast.LENGTH_LONG, force = true)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    bluetoothPermissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    val appSettings = App.provide(requireContext()).settings
                    val cands = BluetoothHelper.driverCandidates(
                        requireContext(), appSettings.nativePreferredDeviceMac, appSettings.lastConnectedNativeMac
                    )
                    val candidates = cands.offered
                    val connectedMacs = cands.connectedOffered.map { it.address }
                    val hasHistory = appSettings.lastConnectedNativeMac.isNotEmpty() ||
                        appSettings.nativePreferredDeviceMac.isNotEmpty() ||
                        appSettings.nativePokeBtMacs.isNotEmpty()

                    val scope = autoScopeMacs(cands, appSettings)
                    val autoTargetMac = NativeDriverSelectionPolicy.resolveAutoConnectTarget(
                        preferredMac = appSettings.nativePreferredDeviceMac,
                        lastUsedMac = NativeDriverSelectionPolicy.lastUsedMac(
                            appSettings.lastConnectedNativeMac, appSettings.nativePokeBtMacs
                        ),
                        connectedMacs = connectedMacs,
                        pairedMacs = candidates.map { it.address },
                        scopeMacs = scope
                    )

                    val shouldShow = NativeDriverSelectionPolicy.shouldShowSelector(
                        mode = appSettings.nativeDriverSelectionMode,
                        pairedCount = candidates.size,
                        connectedCount = connectedMacs.size,
                        scopedCount = scope.size,
                        hasHistory = hasHistory
                    )

                    if (appSettings.nativeDriverSelectionMode == NativeDriverSelectionPolicy.Mode.DISABLED) {
                        if (autoTargetMac != null) {
                            val targetDev = cands.deviceFor(autoTargetMac)
                            val devName = targetDev?.name ?: autoTargetMac
                            connectToNativeDevice(autoTargetMac, devName, connectedMacs, explicit = false)
                        } else {
                            // AapService drops a poke with no MAC, so this branch used to be a
                            // toast and nothing else. Ask which phone instead, which is what
                            // this button did before the selector existed.
                            showNativeAaDeviceSelector(autoCountdown = false)
                        }
                    } else if (!shouldShow && autoTargetMac != null) {
                        val targetDev = cands.deviceFor(autoTargetMac)
                        val devName = targetDev?.name ?: autoTargetMac
                        connectToNativeDevice(autoTargetMac, devName, connectedMacs, explicit = false)
                    } else {
                        showNativeAaDeviceSelector(autoCountdown = false)
                    }
                }
            }
            WifiLauncherMode.MANUAL -> { // Manual (0) -> Open List
                val controller = findNavController()
                if (controller.currentDestination?.id == R.id.homeFragment) {
                    controller.navigate(R.id.action_homeFragment_to_networkListFragment)
                }
            }
        }
    }

    private fun openNetworkList() {
        val controller = findNavController()
        if (controller.currentDestination?.id == R.id.homeFragment) {
            controller.navigate(R.id.action_homeFragment_to_networkListFragment)
        }
    }

    override fun onResume() {
        super.onResume()
        AppLog.i("HomeFragment: onResume. isConnected=${commManager.isConnected}")
        val root = requireView()
        val homeSettings = App.provide(requireContext()).settings
        if (homeSettings.useClassicHome != isClassicHome) reloadHomeLayout()
        applyLayoutSettings(root, homeSettings)
        updateLauncherViews()
        renderHome()
        startHomeInfo()

        val appSettings = App.provide(requireContext()).settings
        if (requestDriverSelection) {
            requestDriverSelection = false
            if (!ExternalBtTransportPolicy.usesExternalModule(NativeAaHandshakeManager.transportRoute(requireContext()))) {
                showNativeAaDeviceSelector(autoCountdown = false)
            }
        } else if (appSettings.wifiConnectionMode == WifiLauncherMode.NATIVE && !commManager.isConnected) {
            if (!hasCheckedNativeDriverSelection) checkNativeDriverSelectionOnStartup()
        }

        activity?.let { act ->
            val isDestroyed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
                act.isDestroyed
            else
                false

            if (!act.isFinishing && !isDestroyed) {
                RenameNotice.maybeShow(act, App.provide(requireContext()).settings)
                Aa174Notice.maybeShow(act, App.provide(requireContext()).settings)
                HomeDesignNotice.maybeShow(act, App.provide(requireContext()).settings) {
                    reloadHomeLayout()
                    view?.let { applyLayoutSettings(it, App.provide(requireContext()).settings) }
                    updateLauncherViews()
                    renderHome()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // A host that is not listening holds an empty widget view, so this runs before the row is
        // built in onResume.
        HomeWidgets.startListening(requireContext())
    }

    override fun onStop() {
        super.onStop()
        HomeWidgets.stopListening()
    }

    override fun onPause() {
        super.onPause()
        driverCountdownTimer?.cancel()
        driverCountdownTimer = null
        stopHomeInfo()
        activeDialog?.dismiss()
        activeDialog = null
        RenameNotice.dismiss()
        Aa174Notice.dismiss()
        HomeDesignNotice.dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        removeClassicGridListener()
    }

    private fun removeClassicGridListener() {
        classicGridListener?.let { listener ->
            view?.findViewById<View>(R.id.main_buttons_layout)?.viewTreeObserver
                ?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(listener)
        }
        classicGridListener = null
    }

    private fun applyLayoutSettings(root: View, homeSettings: Settings) {
        if (isClassicHome) {
            val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            ClassicHomeUiHelper.applyButtonScale(root, homeSettings.homeButtonScalePercent, isPortrait, resources.displayMetrics.density)
            ClassicHomeUiHelper.applyTextColors(root, homeSettings)
            if (isPortrait && classicGridListener == null) {
                classicGridListener = ClassicHomeUiHelper.constrainPortraitGridWidth(root)
            }
        } else {
            HomeUiHelper.applyDriverSide(root, homeSettings.rightHandDrive)
            HomeUiHelper.applyButtonScale(root, homeSettings.homeButtonScalePercent)
            HomeUiHelper.applyTheme(root.context, root, homePalette(root.context))
            // The bar can only be fitted once it has a height to fit into.
            root.doOnLayout { HomeUiHelper.fitDriverBar(it, homeSettings.homeButtonScalePercent) }
        }
    }

    /** Swaps in the other design after the user switched between the new and the classic one. */
    private fun reloadHomeLayout() {
        val container = view as? FrameLayout ?: return
        AppLog.i("HomeFragment: switching to the ${if (App.provide(container.context).settings.useClassicHome) "classic" else "new"} home design")
        removeClassicGridListener()
        inflateHomeContent(container)
    }

    /** The offered phones an automatic pick may name, from the same wake list the poke loop reads. */
    private fun autoScopeMacs(cands: BluetoothHelper.DriverCandidates, settings: Settings): List<String> {
        val notPhones = settings.nativePokeBtMacs.filter {
            cands.verdictOf(it) == DriverCandidatePolicy.Verdict.NOT_A_PHONE
        }.toSet()
        val targets = PokeTargetPolicy.targets(settings.nativePokeBtMacs, settings.nativePokeAllPairedDevices, notPhones)
        return NativeDriverSelectionPolicy.autoScope(cands.offered.map { it.address }, targets)
    }

    /** @return whether this put something on screen, or started connecting, so nothing else should. */
    private fun checkNativeDriverSelectionOnStartup(): Boolean {
        if (!isAdded) return false
        hasCheckedNativeDriverSelection = true
        if (ExternalBtTransportPolicy.usesExternalModule(NativeAaHandshakeManager.transportRoute(requireContext()))) {
            return false
        }
        val appSettings = App.provide(requireContext()).settings
        if (appSettings.nativeDriverSelectionMode == NativeDriverSelectionPolicy.Mode.DISABLED) return false
        // Every answer this check can reach pokes a phone and arms the stack, so a unit the user
        // set to cable only is never asked. The WiFi button still reaches the selector.
        if (WirelessSelectionPolicy.refusesBringUp(
                wirelessSelected = appSettings.showsWifi(),
                userRequested = false,
            )
        ) {
            AppLog.i("HomeFragment: driver selection skipped, wireless is not a chosen connection mode")
            return false
        }
        val adapter = BluetoothHelper.getBluetoothAdapter(requireContext())
        if (adapter == null || !adapter.isEnabled) return false

        // Only the classified phones count, so a connected watch neither becomes the driver nor
        // hides the one phone that is unambiguously here.
        val cands = BluetoothHelper.driverCandidates(
            requireContext(), appSettings.nativePreferredDeviceMac, appSettings.lastConnectedNativeMac
        )
        val targetList = cands.offered
        val connectedMacs = cands.connectedOffered.map { it.address }

        val effectiveLastUsedMac = NativeDriverSelectionPolicy.lastUsedMac(
            appSettings.lastConnectedNativeMac, appSettings.nativePokeBtMacs
        )
        val hasHistory = effectiveLastUsedMac.isNotEmpty() || appSettings.nativePreferredDeviceMac.isNotEmpty()

        val scope = autoScopeMacs(cands, appSettings)
        val shouldShow = NativeDriverSelectionPolicy.shouldShowSelector(
            mode = appSettings.nativeDriverSelectionMode,
            pairedCount = targetList.size,
            connectedCount = connectedMacs.size,
            scopedCount = scope.size,
            hasHistory = hasHistory
        )

        val autoTargetMac = NativeDriverSelectionPolicy.resolveAutoConnectTarget(
            preferredMac = appSettings.nativePreferredDeviceMac,
            lastUsedMac = effectiveLastUsedMac,
            connectedMacs = connectedMacs,
            pairedMacs = targetList.map { it.address },
            scopeMacs = scope
        )

        if (shouldShow) {
            showNativeAaDeviceSelector(autoCountdown = true)
            return true
        } else if (appSettings.nativeDriverSelectionMode == NativeDriverSelectionPolicy.Mode.AUTO && autoTargetMac != null) {
            val targetDev = cands.deviceFor(autoTargetMac)
            val devName = targetDev?.name ?: autoTargetMac
            AppLog.i("HomeFragment: Unambiguous driver ($devName) - auto-connecting directly without prompt")
            connectToNativeDevice(autoTargetMac, devName, connectedMacs, explicit = false)
            return true
        }
        return false
    }

    private fun showNativeAaDeviceSelector(autoCountdown: Boolean = false) {
        if (!isAdded) return
        if (ExternalBtTransportPolicy.usesExternalModule(NativeAaHandshakeManager.transportRoute(requireContext()))) {
            AppLog.i("HomeFragment: Android Bluetooth driver selection does not apply to the external-module route")
            return
        }
        val adapter = BluetoothHelper.getBluetoothAdapter(requireContext())

        if (adapter == null || !adapter.isEnabled) {
            ToastUtils.showToast(requireContext(), getString(R.string.bt_not_enabled), Toast.LENGTH_SHORT, force = true)
            return
        }

        val appSettings = App.provide(requireContext()).settings
        val preferredMac = appSettings.nativePreferredDeviceMac
        val lastUsedMac = appSettings.lastConnectedNativeMac
        val cands = BluetoothHelper.driverCandidates(requireContext(), preferredMac, lastUsedMac)
        val bondedDevices = cands.all.map { it.device }
        if (bondedDevices.isEmpty()) {
            ToastUtils.showToast(requireContext(), "No paired Bluetooth devices found", Toast.LENGTH_SHORT, force = true)
            return
        }

        // Row labels say who is here, phone or not. The count and the countdown target below ask
        // only the phones, and an unreadable presence leaves every row unlabelled.
        val connectedMacs = cands.connectedAll.map { it.address }
        val presenceReadable = cands.presenceReadable

        val likelyPhones = cands.offered
        var showAllDevices = likelyPhones.isEmpty()
        val initialCandidates = if (showAllDevices) bondedDevices else likelyPhones

        fun sortDeviceList(devices: List<BluetoothDevice>): List<BluetoothDevice> {
            return devices.sortedWith(
                compareByDescending<BluetoothDevice> { it.address in connectedMacs }
                    .thenByDescending { it.address.equals(preferredMac, ignoreCase = true) }
                    .thenByDescending { it.address.equals(lastUsedMac, ignoreCase = true) }
                    .thenBy { it.name ?: "" }
            )
        }

        val currentDevices = mutableListOf<BluetoothDevice>()
        currentDevices.addAll(sortDeviceList(initialCandidates))

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_driver_selector, null)
        val countdownContainer = dialogView.findViewById<View>(R.id.countdownContainer)
        val countdownSubtitle = dialogView.findViewById<TextView>(R.id.countdownSubtitle)
        val countdownProgress = dialogView.findViewById<ProgressBar>(R.id.countdownProgress)
        val deviceListView = dialogView.findViewById<ListView>(R.id.driverDeviceList)
        val btnToggleFilter = dialogView.findViewById<TextView>(R.id.btnToggleFilter)

        val brandTeal = ContextCompat.getColor(requireContext(), R.color.brand_teal)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            countdownProgress.progressTintList = ColorStateList.valueOf(brandTeal)
        }

        val connectedMacSet = connectedMacs.toSet()
        val listAdapter = object : ArrayAdapter<BluetoothDevice>(requireContext(), R.layout.list_item_driver_device, currentDevices) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.list_item_driver_device, parent, false)
                val device = getItem(position) ?: return view
                val nameView = view.findViewById<TextView>(R.id.deviceName)
                val statusView = view.findViewById<TextView>(R.id.deviceStatus)
                val badgeView = view.findViewById<TextView>(R.id.badgeText)

                val iconView = view.findViewById<ImageView>(R.id.deviceIcon)
                if (cands.verdictOf(device.address) != DriverCandidatePolicy.Verdict.NOT_A_PHONE) {
                    iconView.setImageResource(R.drawable.ic_phone)
                } else {
                    iconView.setImageResource(R.drawable.ic_headphones)
                }

                nameView.text = device.name ?: "Unknown Device"

                when (NativeDriverSelectionPolicy.presenceOf(device.address, connectedMacSet, presenceReadable)) {
                    NativeDriverSelectionPolicy.DriverPresence.CONNECTED -> {
                        statusView.text = "🟢 " + context.getString(R.string.driver_device_connected)
                        statusView.setTextColor(ContextCompat.getColor(context, R.color.brand_teal))
                    }
                    NativeDriverSelectionPolicy.DriverPresence.DISCONNECTED -> {
                        statusView.text = context.getString(R.string.driver_device_disconnected)
                        statusView.setTextColor(Color.LTGRAY)
                    }
                    NativeDriverSelectionPolicy.DriverPresence.UNKNOWN -> {
                        statusView.text = context.getString(R.string.driver_device_paired)
                        statusView.setTextColor(Color.LTGRAY)
                    }
                }

                when {
                    device.address.equals(preferredMac, ignoreCase = true) -> {
                        badgeView.text = "⭐ " + context.getString(R.string.driver_device_preferred)
                        badgeView.visibility = View.VISIBLE
                        badgeView.setBackgroundResource(R.drawable.bg_setting_single)
                    }
                    device.address.equals(lastUsedMac, ignoreCase = true) -> {
                        badgeView.text = context.getString(R.string.driver_device_last_used)
                        badgeView.visibility = View.VISIBLE
                        badgeView.setBackgroundResource(R.drawable.bg_setting_single)
                    }
                    else -> {
                        badgeView.visibility = View.GONE
                    }
                }

                val isTop = position == 0
                val isBottom = position == count - 1
                val bgRes = when {
                    isTop && isBottom -> R.drawable.bg_setting_single
                    isTop -> R.drawable.bg_setting_top
                    isBottom -> R.drawable.bg_setting_bottom
                    else -> R.drawable.bg_setting_middle
                }
                view.setBackgroundResource(bgRes)
                return view
            }
        }

        val dm = resources.displayMetrics
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val maxListHeight = if (isLandscape) {
            (dm.heightPixels * 0.38).toInt().coerceIn((120 * dm.density).toInt(), (200 * dm.density).toInt())
        } else {
            (dm.heightPixels * 0.45).toInt().coerceIn((180 * dm.density).toInt(), (320 * dm.density).toInt())
        }
        deviceListView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            maxListHeight
        )
        deviceListView.adapter = listAdapter

        val hiddenCount = bondedDevices.size - likelyPhones.size
        if (hiddenCount > 0 && likelyPhones.isNotEmpty()) {
            btnToggleFilter.visibility = View.VISIBLE
            fun updateToggleLabel() {
                btnToggleFilter.text = if (showAllDevices) {
                    getString(R.string.show_only_phones)
                } else {
                    "${getString(R.string.show_all_devices)} ($hiddenCount)"
                }
            }
            updateToggleLabel()
            btnToggleFilter.setOnClickListener {
                // Pause/cancel auto-countdown timer since the user is actively interacting with the device list
                driverCountdownTimer?.cancel()
                driverCountdownTimer = null
                countdownContainer.visibility = View.GONE

                showAllDevices = !showAllDevices
                currentDevices.clear()
                val nextCandidates = if (showAllDevices) bondedDevices else likelyPhones
                currentDevices.addAll(sortDeviceList(nextCandidates))
                listAdapter.notifyDataSetChanged()
                updateToggleLabel()
            }
        } else {
            btnToggleFilter.visibility = View.GONE
        }

        // Set by every path that ends the prompt with an answer, so the dismiss below can tell
        // "the user left" from "the user chose".
        var selectionResolved = false

        val cancelDriverSelection = {
            selectionResolved = true
            driverCountdownTimer?.cancel()
            driverCountdownTimer = null
            activeDialog = null
            val cancelIntent = Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_NATIVE_AA_CANCEL_POKE
            }
            ContextCompat.startForegroundService(requireContext(), cancelIntent)
        }

        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.driver_selection_dialog_title)
            .setView(dialogView)
            .setNegativeButton(R.string.cancel) { d, _ ->
                cancelDriverSelection()
                d.dismiss()
            }
            .create()

        dialog.setOnCancelListener {
            cancelDriverSelection()
        }

        dialog.setOnDismissListener {
            driverCountdownTimer?.cancel()
            driverCountdownTimer = null
            activeDialog = null
            val ctx = context
            if (!selectionResolved && ctx != null) {
                // onPause dismisses the dialog, which reaches here and never onCancel. Without
                // this the prompt flag stayed set and every phone was refused.
                val dismissIntent = Intent(ctx, AapService::class.java).apply {
                    action = AapService.ACTION_NATIVE_AA_PROMPT_DISMISSED
                }
                ContextCompat.startForegroundService(ctx, dismissIntent)
            }
        }

        val effectiveLastUsed = NativeDriverSelectionPolicy.lastUsedMac(
            lastUsedMac, appSettings.nativePokeBtMacs
        )
        // Scoped to the phones whatever "Show all" is showing, so widening the list never changes
        // what the countdown connects to.
        val autoTargetMac = NativeDriverSelectionPolicy.resolveAutoConnectTarget(
            preferredMac = preferredMac,
            lastUsedMac = effectiveLastUsed,
            connectedMacs = cands.connectedOffered.map { it.address },
            pairedMacs = likelyPhones.map { it.address },
            scopeMacs = autoScopeMacs(cands, appSettings)
        )
        val autoTargetDevice = cands.deviceFor(autoTargetMac)
        val targetName = autoTargetDevice?.name ?: autoTargetMac ?: ""

        deviceListView.setOnItemClickListener { _, _, position, _ ->
            selectionResolved = true
            driverCountdownTimer?.cancel()
            driverCountdownTimer = null
            dialog.dismiss()
            val chosen = currentDevices.getOrNull(position) ?: return@setOnItemClickListener
            connectToNativeDevice(chosen.address, chosen.name ?: "Device", connectedMacs, explicit = true)
        }

        val timeoutSec = NativeDriverSelectionPolicy.sanitizeTimeout(appSettings.nativeDriverSelectionTimeoutSec)

        if (autoCountdown && autoTargetMac != null) {
            countdownContainer.visibility = View.VISIBLE
            countdownProgress.max = timeoutSec * 1000
            countdownProgress.progress = timeoutSec * 1000

            var lastSecondsRemaining = -1
            driverCountdownTimer?.cancel()
            driverCountdownTimer = object : CountDownTimer(timeoutSec * 1000L, 100L) {
                override fun onTick(millisUntilFinished: Long) {
                    if (!isAdded || dialog.isShowing != true) return
                    countdownProgress.progress = millisUntilFinished.toInt()
                    val secondsRemaining = ((millisUntilFinished + 999) / 1000).toInt()
                    if (secondsRemaining != lastSecondsRemaining) {
                        lastSecondsRemaining = secondsRemaining
                        countdownSubtitle.text = getString(R.string.driver_selection_auto_in, secondsRemaining, targetName)
                    }
                }

                override fun onFinish() {
                    if (!isAdded || dialog.isShowing != true) return
                    selectionResolved = true
                    dialog.dismiss()
                    connectToNativeDevice(autoTargetMac, targetName, connectedMacs, explicit = false)
                }
            }
            countdownSubtitle.text = getString(R.string.driver_selection_auto_in, timeoutSec, targetName)
        } else {
            countdownContainer.visibility = View.GONE
        }

        dialog.setOnShowListener {
            // An activity relaunch between show() and this callback leaves it queued against a
            // destroyed fragment; the fragment that replaced this one opens its own selector.
            val ctx = context ?: return@setOnShowListener
            (activity as? MainActivity)?.dismissSplashImmediately()
            val promptIntent = Intent(ctx, AapService::class.java).apply {
                action = AapService.ACTION_NATIVE_AA_PROMPT_SHOWN
            }
            ContextCompat.startForegroundService(ctx, promptIntent)
            if (autoCountdown && autoTargetMac != null) {
                driverCountdownTimer?.start()
            }
        }

        activeDialog = dialog
        dialog.show()
    }

    /**
     * Wakes [mac] and shows the connect UI. The wake may take several rounds or never be answered,
     * so every path gets the non-blocking pill with its step line; the full-screen overlay takes
     * over once the phone answers. The pill names the phone, so no toast repeats it.
     */
    private fun connectToNativeDevice(mac: String, name: String, connectedMacs: Collection<String>, explicit: Boolean) {
        val reachable = NativeDriverSelectionPolicy.connectUiIsImmediate(mac, connectedMacs)
        AppLog.i("HomeFragment: Connecting to Native-AA device: $name ($mac), btConnected=$reachable")
        // Without a status text the indicator falls back to "Android Auto is starting", which is the
        // claim the pill exists to avoid making about a phone that still has to be woken.
        val statusText = if (reachable) getString(R.string.connecting_driver, name)
                         else getString(R.string.connecting_driver_disconnected, name)
        (requireActivity() as? MainActivity)?.beginAutoConnect(
            "Native-AA driver: $name",
            MainActivity.ConnectionUiMode.PILL_THEN_OVERLAY,
            statusText,
            statusTextIsWakeClaim = !reachable
        )
        val intent = Intent(requireContext(), AapService::class.java).apply {
            action = AapService.ACTION_NATIVE_AA_POKE
            putExtra(AapService.EXTRA_MAC, mac)
            putExtra(AapService.EXTRA_EXPLICIT_PICK, explicit)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun showNearbyDeviceSelector() {
        // Ensure NearbyManager discovery is running via AapService
        ContextCompat.startForegroundService(requireContext(),
            Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_START_WIRELESS_SCAN
            })

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_nearby_selection, null)
        val deviceListView = dialogView.findViewById<ListView>(R.id.deviceList)
        val searchingText = dialogView.findViewById<TextView>(R.id.searchingText)
        val connectionProgress = dialogView.findViewById<ProgressBar>(R.id.connectionProgress)

        // Ensure the loading spinner is visible in both Light and Dark modes by forcing our brand color.
        val brandTeal = ContextCompat.getColor(requireContext(), R.color.brand_teal)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            connectionProgress.indeterminateTintList = ColorStateList.valueOf(brandTeal)
            connectionProgress.indeterminateTintMode = android.graphics.PorterDuff.Mode.SRC_IN
        } else {
            @Suppress("DEPRECATION")
            connectionProgress.indeterminateDrawable?.setColorFilter(brandTeal, android.graphics.PorterDuff.Mode.SRC_IN)
        }

        // Custom adapter to handle rounded backgrounds like in USB/Network lists
        val listAdapter = object : ArrayAdapter<NearbyManager.DiscoveredEndpoint>(requireContext(), R.layout.list_item_nearby) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.list_item_nearby, parent, false)
                val endpoint = getItem(position)
                view.findViewById<TextView>(R.id.deviceName).text = endpoint?.name ?: "Unknown"

                // Apply rounded backgrounds based on position
                val isTop = position == 0
                val isBottom = position == count - 1
                val bgRes = when {
                    isTop && isBottom -> R.drawable.bg_setting_single
                    isTop -> R.drawable.bg_setting_top
                    isBottom -> R.drawable.bg_setting_bottom
                    else -> R.drawable.bg_setting_middle
                }
                view.setBackgroundResource(bgRes)
                return view
            }
        }
        deviceListView.adapter = listAdapter

        var collectJob: Job? = null

        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(getString(R.string.searching)) // Initial title
            .setView(dialogView)
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener {
                collectJob?.cancel()
                if (activeDialog == it) activeDialog = null
            }
            .create()

        activeDialog = dialog

        deviceListView.setOnItemClickListener { _, _, which, _ ->
            val endpoints = NearbyManager.discoveredEndpoints.value
            if (which < endpoints.size) {
                val endpoint = endpoints[which]
                AppLog.i("HomeFragment: Selected Nearby device: ${endpoint.name} (${endpoint.id})")

                // Hand off to the auto-connect overlay so the user gets the same
                // visual treatment as every other connection path. Pass the
                // endpoint name so the loading screen can show "Connecting to
                // <device>…" instead of the generic status text.
                val statusText = getString(R.string.connecting_to_nearby, endpoint.name)
                dialog.dismiss()
                (requireActivity() as? MainActivity)?.beginAutoConnect(
                    "manual Nearby select ${endpoint.name}",
                    MainActivity.ConnectionUiMode.OVERLAY,
                    statusText
                )

                val intent = Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_NEARBY_CONNECT
                    putExtra(AapService.EXTRA_ENDPOINT_ID, endpoint.id)
                }
                ContextCompat.startForegroundService(requireContext(), intent)
            }
        }

        dialog.show()

        // Live-update the dialog list as endpoints are discovered
        collectJob = viewLifecycleOwner.lifecycleScope.launch {
            NearbyManager.discoveredEndpoints.collect { endpoints ->
                listAdapter.clear()
                listAdapter.addAll(endpoints)
                listAdapter.notifyDataSetChanged()

                if (endpoints.isEmpty()) {
                    dialog.setTitle(getString(R.string.searching))
                    searchingText.visibility = View.GONE
                } else {
                    dialog.setTitle(getString(R.string.nearby_device_found))
                    searchingText.visibility = View.VISIBLE
                    searchingText.text = getString(R.string.select_nearby_device) + " (${endpoints.size})"
                }
            }
        }
    }

    companion object {
        private var hasAutoStarted = false
        private var hasCheckedNativeDriverSelection = false
        var forceSelfModeLaunch = false
        var requestDriverSelection = false
        private const val MINUTE_MS = 60_000L
        private const val CLOCK_SLACK_MS = 250L
        private const val SPEED_INTERVAL_MS = 1_000L
        /** Older than this and the speed is shown as unknown: a stale reading is worse than none. */
        private const val SPEED_MAX_AGE_MS = 10_000L
        /** Where the main button's card stops fitting and the favorites take over. */
        private const val COMPACT_HEIGHT_DP = 360
        private const val COMPACT_WIDTH_DP = 400
        fun resetAutoStart() {
            hasAutoStarted = false
            hasCheckedNativeDriverSelection = false
        }
    }
}
