package org.grapheneos.apps.client.ui.container

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.Observer
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.NavigationUI
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import dagger.hilt.android.AndroidEntryPoint
import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.R
import org.grapheneos.apps.client.databinding.ActivityMainBinding
import org.grapheneos.apps.client.item.InstallCallBack
import org.grapheneos.apps.client.item.PackageInfo
import org.grapheneos.apps.client.service.SeamlessUpdaterJob
import org.grapheneos.apps.client.ui.detailsScreen.DetailsScreenArgs
import org.grapheneos.apps.client.ui.search.SearchScreenState
import org.grapheneos.apps.client.utils.hideKeyboard
import org.grapheneos.apps.client.utils.isInstallBlockedByAdmin
import org.grapheneos.apps.client.utils.showKeyboard
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var searchState: SearchScreenState
    private lateinit var views: ActivityMainBinding
    private val navCtrl by lazy {
        val navHostFragment =
            supportFragmentManager.findFragmentById(views.container.id) as NavHostFragment

        navHostFragment.navController
    }
    private val appBarConfiguration by lazy {
        AppBarConfiguration.Builder(setOf(R.id.mainScreen, R.id.updatesScreen))
            .build()
    }
    private val state: MainActivityState by viewModels()

    private val obs = Observer<Int> { updatableCount ->
        if (updatableCount == 0) {
            views.bottomNavView.removeBadge(R.id.updatesScreen)
        } else {
            views.bottomNavView.getOrCreateBadge(R.id.updatesScreen).number = updatableCount
        }

    }

    var isMainScreen = false
    var isSearchScreen = false
    var isSyncScreen = false
    var isDetailsScreen = false
    var currentDestinations = -1
    private val packagesObserver = Observer<Map<String, PackageInfo>> { updateUi(it.isNotEmpty()) }

    private fun updateUi(isSyncFinished: Boolean = app.isSyncingSuccessful()) {
        views.searchBar.isVisible = (isMainScreen || isSearchScreen) && isSyncFinished
        views.searchTitle.isVisible = isMainScreen && isSyncFinished
        views.searchInput.isVisible = isSearchScreen && isSyncFinished
        views.toolbar.isVisible = isSyncFinished && !isSyncScreen
        views.bottomNavView.isGone =
            !appBarConfiguration.topLevelDestinations.contains(currentDestinations) || !isSyncFinished || isSyncScreen
        if (isSearchScreen) {
            views.searchInput.showKeyboard()
        } else {
            views.searchInput.hideKeyboard()
        }
    }

    private val app by lazy {
        applicationContext as App
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        views = ActivityMainBinding.inflate(layoutInflater)
        setContentView(views.root)
        setSupportActionBar(views.toolbar)
        window.setDecorFitsSystemWindows(false)

        ViewCompat.setOnApplyWindowInsetsListener(views.root) { v, insets ->
            val paddingInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
            )

            v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = paddingInsets.left
                rightMargin = paddingInsets.right
            }
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(
            views.toolbar
        ) { v, insets ->

            val paddingInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
            )

            v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = paddingInsets.top
            }
            insets
        }

        if (SeamlessUpdaterJob.NOTIFICATION_ACTION == intent.action) {
            (getSystemService(NotificationManager::class.java)).cancel(
                SeamlessUpdaterJob.NOTIFICATION_ID
            )
        }
        NavigationUI.setupWithNavController(views.bottomNavView, navCtrl)
        setupActionBarWithNavController(navCtrl, appBarConfiguration)

        navCtrl.addOnDestinationChangedListener { _, destination, _ ->
            isMainScreen = destination.id == R.id.mainScreen
            isSearchScreen = destination.id == R.id.searchScreen
            isSyncScreen = destination.id == R.id.syncScreen
            isDetailsScreen = destination.id == R.id.detailsScreen
            currentDestinations = destination.id
            updateUi()
        }
        views.searchInput.setOnClickListener { navCtrl.navigate(R.id.searchScreen) }
        views.searchBar.setOnClickListener { navCtrl.navigate(R.id.searchScreen) }
        app.updateCount.observe(this, obs)
        app.packageLiveData.observe(this, packagesObserver)

        views.searchInput.addTextChangedListener { editable ->
            searchState.updateQuery(editable?.trim()?.toString() ?: "")
        }

        when (intent?.action) {
            Intent.ACTION_SHOW_APP_INFO -> {
                // We should only show app info one time in onCreate.
                if (state.shouldShowAppInfo) {
                    state.shouldShowAppInfo = false
                    intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME)
                        ?.let { onActionShowAppInfo(it) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)

        when (intent?.action) {
            Intent.ACTION_SHOW_APP_INFO -> {
                intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME)?.let { onActionShowAppInfo(it) }
            }
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        if (isInstallBlockedByAdmin()) {
            navCtrl.navigate(R.id.installRestrictionScreen)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.clear -> {
                views.searchInput.text?.clear()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }


    override fun onSupportNavigateUp(): Boolean {
        return navCtrl.navigateUp(appBarConfiguration)
                || super.onSupportNavigateUp()
    }

    fun navigateToErrorScreen(status: InstallCallBack) {
        navCtrl.navigate(R.id.installErrorScreen, Bundle().apply {
            putParcelable("error", status)
        })
    }

    private fun navigateToDetailsScreen(
        pkgName: String,
        label: String,
        installationRequested: Boolean = false,
        actionShowAppInfo: Boolean = false
    ) {
        val args = DetailsScreenArgs.Builder(
            pkgName,
            label,
            installationRequested,
            actionShowAppInfo
        ).build().toBundle()
        navCtrl.navigate(R.id.detailsScreen, args)
    }

    private fun onActionShowAppInfo(pkgName: String) {
        if (isDetailsScreen) {
            navCtrl.popBackStack()
        }
        navigateToDetailsScreen(
            pkgName,
            label = getString(R.string.app_details_label),
            actionShowAppInfo = true
        )
    }
}
