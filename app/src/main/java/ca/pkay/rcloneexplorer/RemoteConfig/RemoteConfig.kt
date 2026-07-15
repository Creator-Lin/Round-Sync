package ca.pkay.rcloneexplorer.RemoteConfig

import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.SearchView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Rclone
import ca.pkay.rcloneexplorer.RemoteConfig.ProviderListFragment.Companion.newProviderListConfig
import ca.pkay.rcloneexplorer.RemoteConfig.ProviderListFragment.ProviderSelectedListener
import ca.pkay.rcloneexplorer.RuntimeConfiguration
import ca.pkay.rcloneexplorer.rclone.Provider
import ca.pkay.rcloneexplorer.quarkdav.QuarkDavRemote
import ca.pkay.rcloneexplorer.util.ActivityHelper
import es.dmoral.toasty.Toasty
import org.json.JSONException


class RemoteConfig : AppCompatActivity(), ProviderSelectedListener {

    private val OUTSTATE_TITLE = "ca.pkay.rcexplorer.remoteConfig.TITLE"
    private var mFragment: Fragment? = null
    private lateinit var mSearchBar: SearchView
    private var mSearchIcon: MenuItem? = null

    companion object {
        const val CONFIG_EDIT_CODE = 139
        const val CONFIG_EDIT_TARGET = "CONFIG_EDIT_TARGET"
        const val QUARKDAV_EDIT_TARGET = "QUARKDAV_EDIT_TARGET"
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(RuntimeConfiguration.attach(this, newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyTheme()
        setContentView(R.layout.activity_remote_config)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        val actionBar = supportActionBar
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true)
            actionBar.setDisplayShowHomeEnabled(true)
        }

        mSearchBar = findViewById(R.id.search)
        if (savedInstanceState != null) {
            mFragment = supportFragmentManager.findFragmentById(R.id.flFragment)
                ?: supportFragmentManager.findFragmentByTag("new config")
                ?: newProviderListConfig()
            savedInstanceState.getString(OUTSTATE_TITLE)?.let { supportActionBar?.title = it }
            installSearchListeners()
            return
        }

        val fragmentTransaction = supportFragmentManager.beginTransaction()
        mFragment = newProviderListConfig()
        fragmentTransaction.replace(R.id.flFragment, getCurrentFragment(), "config list")
        fragmentTransaction.commit()

        val quarkDavEdit = intent.getStringExtra(QUARKDAV_EDIT_TARGET)
        if (!quarkDavEdit.isNullOrEmpty()) {
            mFragment = QuarkDavConfigFragment.newInstance(quarkDavEdit)
            startQuarkDavConfig()
        } else {
            val shouldEdit = intent.getStringExtra(CONFIG_EDIT_TARGET)
            if (!shouldEdit.isNullOrEmpty()) {
                val rclone = Rclone(this)
                val config = rclone.getConfig(shouldEdit)
                if (config != null) {
                    try {
                        val provider = rclone.getProvider(config["type"])
                        mFragment = DynamicRemoteConfigFragment(provider.name, config)
                        startConfig(provider)
                    } catch (e: JSONException) {
                        throw RuntimeException(e)
                    }
                }
            }
        }

        installSearchListeners()

    }

    private fun installSearchListeners() {
        mSearchBar.setOnCloseListener {
            toggleSearch(false)
            true
        }
        mSearchBar.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextChange(newText: String): Boolean {
                when (val current = getCurrentFragment()) {
                    is ProviderListFragment -> current.setSearchterm(newText)
                    is DynamicRemoteConfigFragment -> current.setSearchterm(newText)
                    is QuarkDavConfigFragment -> current.setSearchterm(newText)
                }
                return true
            }

            override fun onQueryTextSubmit(query: String): Boolean = true
        })
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.remote_config_menu, menu)
        mSearchIcon = menu?.findItem(R.id.app_bar_search);
        return true
    }

    private fun toggleSearch(show: Boolean) {
        if(show) {
            mSearchBar.visibility = View.VISIBLE
            mSearchBar.requestFocus()
            mSearchBar.isFocusable = true
            mSearchBar.isIconified = false
            mSearchBar.requestFocusFromTouch()
            mSearchIcon?.isVisible = false
        } else {
            mSearchBar.visibility = View.GONE
            mSearchIcon?.isVisible = true
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.app_bar_search -> {
                toggleSearch(true)
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (supportActionBar != null) {
            val title = supportActionBar!!.title
            if (title != null) {
                outState.putString(OUTSTATE_TITLE, title.toString())
            }
        }
    }
    override fun onSupportNavigateUp(): Boolean {
        handleBackAction()
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleBackAction()
    }

    private fun handleBackAction() {
        if(mSearchBar.visibility == View.VISIBLE) {
            toggleSearch(false)
            return
        }

        if (mFragment is ProviderListFragment) {
            finish()
        } else if (mFragment is DynamicRemoteConfigFragment) {
            if ((mFragment as DynamicRemoteConfigFragment).isEditConfig()) {
                finish()
            } else {
                val title = supportActionBar?.title?.toString()
                startProviderlist(title)
            }
        } else if (mFragment is QuarkDavConfigFragment) {
            if ((mFragment as QuarkDavConfigFragment).isEditConfig()) finish() else startProviderlist(QuarkDavRemote.PROVIDER_NAME)
        } else {
            startProviderlist()
        }
    }

    override fun onProviderSelected(provider: Provider) {
        if (provider == null) {
            Toasty.error(this, getString(R.string.nothing_selected), Toast.LENGTH_SHORT, true)
                .show()
            return
        }
        toggleSearch(false)
        if (provider.name == QuarkDavRemote.PROVIDER_NAME) {
            mFragment = QuarkDavConfigFragment.newInstance()
            startQuarkDavConfig()
        } else {
            mFragment = DynamicRemoteConfigFragment(provider.name)
            startConfig(provider)
        }
    }

    private fun applyTheme() {
        ActivityHelper.applyTheme(this)
        val typedValue = TypedValue()
        theme.resolveAttribute(R.attr.colorPrimaryDark, typedValue, true)
        window.statusBarColor = typedValue.data
    }

    private fun startConfig(provider: Provider) {
        val transaction = supportFragmentManager.beginTransaction()
        transaction.replace(R.id.flFragment, getCurrentFragment(), "new config")
        transaction.commit()
        if (supportActionBar != null) {
            supportActionBar!!.title = provider.getNameCapitalized()
        }
    }

    private fun startQuarkDavConfig() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.flFragment, getCurrentFragment(), "new config")
            .commit()
        supportActionBar?.setTitle(R.string.quarkdav_title)
    }

    private fun startProviderlist() {
        startProviderlist(null)
    }
    private fun startProviderlist(preselection: String?) {
        mFragment = newProviderListConfig(preselection)
        val fragmentTransaction = supportFragmentManager.beginTransaction()
        fragmentTransaction.replace(R.id.flFragment, getCurrentFragment(), "config list")
        fragmentTransaction.commit()
        if (supportActionBar != null) {
            supportActionBar!!.setTitle(R.string.title_activity_remote_config)
        }
    }

    private fun isProviderlist(): Boolean {
        if (mFragment is ProviderListFragment) {
            return true
        }
        return false
    }
    private fun isDynamicRemoteConfigFragment(): Boolean {
        if (mFragment is DynamicRemoteConfigFragment) {
            return true
        }
        return false
    }


    /**
     * This defauls to the provider list
     */
    private fun getCurrentFragment(): Fragment {
        if(mFragment == null) {
            return newProviderListConfig()
        }
        return mFragment!!
    }
}