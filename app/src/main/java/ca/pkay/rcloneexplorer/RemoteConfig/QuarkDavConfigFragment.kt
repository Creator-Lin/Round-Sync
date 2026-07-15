package ca.pkay.rcloneexplorer.RemoteConfig

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.quarkdav.QuarkDavRemote
import ca.pkay.rcloneexplorer.quarkdav.QuarkDavRepository
import ca.pkay.rcloneexplorer.quarkdav.QuarkDavServiceActions
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import es.dmoral.toasty.Toasty

/** Round-Sync styled editor for the cookie-only QuarkDav kernel. */
class QuarkDavConfigFragment : Fragment() {
    companion object {
        private const val ARG_REMOTE_ID = "remote_id"

        @JvmStatic
        fun newInstance(remoteId: String? = null) = QuarkDavConfigFragment().apply {
            arguments = Bundle().apply { remoteId?.let { putString(ARG_REMOTE_ID, it) } }
        }
    }

    private lateinit var form: LinearLayout
    private lateinit var remoteName: EditText
    private var original: QuarkDavRemote? = null
    private var filter = ""
    private val searchableCards = mutableListOf<Pair<String, View>>()

    private lateinit var enabled: CheckBox
    private lateinit var startAtBoot: CheckBox
    private lateinit var keepCpuAwake: CheckBox
    private lateinit var listenAddress: EditText
    private lateinit var port: EditText
    private lateinit var prefix: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var noAuth: CheckBox
    private lateinit var cacheTtl: EditText
    private lateinit var tempDir: EditText
    private lateinit var cookie: EditText
    private lateinit var rootId: EditText
    private lateinit var brand: AutoCompleteTextView
    private lateinit var orderBy: AutoCompleteTextView
    private lateinit var orderDirection: AutoCompleteTextView
    private lateinit var useTranscoding: CheckBox
    private lateinit var onlyVideo: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        original = arguments?.getString(ARG_REMOTE_ID)?.let { QuarkDavRepository.get(requireContext(), it) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.remote_config_form, container, false)
        form = view.findViewById(R.id.form_content)
        remoteName = view.findViewById(R.id.remote_name)
        view.findViewById<TextView>(R.id.labelTitle).setText(R.string.remote_properties_remote_name)
        remoteName.setText(original?.name ?: "QuarkDav")

        buildForm()
        view.findViewById<FloatingActionButton>(R.id.finish).setOnClickListener { save() }
        applyFilter()
        return view
    }

    fun isEditConfig(): Boolean = original != null

    fun setSearchterm(term: String) {
        filter = term.trim()
        if (::form.isInitialized) applyFilter()
    }

    private fun buildForm() {
        val model = original ?: QuarkDavRemote()

        enabled = checkField(R.string.quarkdav_enabled, model.enabled)
        startAtBoot = checkField(R.string.quarkdav_start_at_boot, model.startAtBoot)
        keepCpuAwake = checkField(R.string.quarkdav_keep_awake, model.keepCpuAwake)
        addBatteryCard()

        listenAddress = textField(R.string.quarkdav_listen_address, model.listenAddress)
        port = textField(R.string.quarkdav_port, model.port.toString(), InputType.TYPE_CLASS_NUMBER)
        prefix = textField(R.string.quarkdav_prefix, model.prefix)
        username = textField(R.string.quarkdav_username, model.username)
        password = textField(
            R.string.quarkdav_password,
            model.password,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )
        noAuth = checkField(R.string.quarkdav_no_auth, model.noAuth)
        cacheTtl = textField(R.string.quarkdav_cache_ttl, model.cacheTtlSeconds.toString(), InputType.TYPE_CLASS_NUMBER)
        tempDir = textField(R.string.quarkdav_temp_dir, model.tempDir)

        cookie = textField(
            R.string.quarkdav_cookie,
            model.cookie,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            getString(R.string.quarkdav_cookie_help),
            4,
        )
        rootId = textField(R.string.quarkdav_root_id, model.rootId)
        brand = dropdownField(R.string.quarkdav_brand, model.brand, listOf("quark", "uc"))
        orderBy = dropdownField(
            R.string.quarkdav_order_by,
            model.orderBy,
            listOf("none", "file_name", "updated_at", "created_at", "file_size"),
        )
        orderDirection = dropdownField(R.string.quarkdav_order_direction, model.orderDirection, listOf("asc", "desc"))
        useTranscoding = checkField(R.string.quarkdav_use_transcoding, model.useTranscodingAddress)
        onlyVideo = checkField(R.string.quarkdav_only_video, model.onlyListVideoFile)
    }

    private fun textField(
        titleRes: Int,
        value: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        description: String? = null,
        minLines: Int = 1,
    ): EditText {
        val layout = baseCardContent(titleRes, description)
        val inputLayout = TextInputLayout(requireContext()).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = getString(titleRes)
            setPadding(0, resources.getDimensionPixelOffset(R.dimen.cardPadding), 0, 0)
        }
        val edit = TextInputEditText(inputLayout.context).apply {
            this.inputType = inputType
            setText(value)
            this.minLines = minLines
            if (minLines > 1) setHorizontallyScrolling(false)
            resources.getDimensionPixelOffset(R.dimen.cardPadding).let { setPadding(it, it, it, it) }
        }
        addTextInputChild(inputLayout, edit)
        layout.addView(inputLayout)
        addSearchableCard(getString(titleRes) + " " + (description ?: ""), layout)
        return edit
    }

    private fun dropdownField(titleRes: Int, value: String, values: List<String>): AutoCompleteTextView {
        val layout = baseCardContent(titleRes, null)
        val inputLayout = TextInputLayout(
            ContextThemeWrapper(requireContext(), R.style.Widget_MaterialComponents_TextInputLayout_OutlinedBox_ExposedDropdownMenu),
        ).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = getString(titleRes)
            setPadding(0, resources.getDimensionPixelOffset(R.dimen.cardPadding), 0, 0)
        }
        val input = AutoCompleteTextView(inputLayout.context).apply {
            setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, values))
            setText(value, false)
            inputType = InputType.TYPE_NULL
            resources.getDimensionPixelOffset(R.dimen.cardPadding).let { setPadding(it, it, it, it) }
        }
        addTextInputChild(inputLayout, input)
        layout.addView(inputLayout)
        addSearchableCard(getString(titleRes), layout)
        return input
    }

    /**
     * TextInputLayout extends LinearLayout and reuses the EditText child layout params for its
     * internal input frame. Supplying plain ViewGroup.LayoutParams therefore crashes when
     * TextInputLayout updates its margins and casts them to LinearLayout.LayoutParams.
     */
    private fun addTextInputChild(inputLayout: TextInputLayout, input: EditText) {
        inputLayout.addView(
            input,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun checkField(titleRes: Int, checked: Boolean): CheckBox {
        val layout = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        val check = CheckBox(requireContext()).apply {
            setText(titleRes)
            isChecked = checked
        }
        layout.addView(check)
        addSearchableCard(getString(titleRes), layout)
        return check
    }

    private fun addBatteryCard() {
        val pm = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignored = pm.isIgnoringBatteryOptimizations(requireContext().packageName)
        val description = getString(if (ignored) R.string.quarkdav_battery_unrestricted else R.string.quarkdav_battery_restricted)
        val layout = baseCardContent(R.string.quarkdav_battery_settings, description)
        val button = Button(requireContext()).apply {
            setText(R.string.quarkdav_battery_settings)
            setOnClickListener {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${requireContext().packageName}")
                }
                runCatching { startActivity(intent) }.onFailure {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        }
        layout.addView(button)
        addSearchableCard(getString(R.string.quarkdav_battery_settings) + " " + description, layout)
    }

    private fun baseCardContent(titleRes: Int, description: String?): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(requireContext()).apply {
                setText(titleRes)
                typeface = Typeface.DEFAULT_BOLD
            })
            if (!description.isNullOrBlank()) {
                addView(TextView(requireContext()).apply { text = description })
            }
        }
    }

    private fun addSearchableCard(searchText: String, content: View) {
        val card = CardView(requireContext()).apply {
            val margin = dp(8)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(margin, dp(8), margin, dp(4))
            }
            setCardBackgroundColor(resolveColor(R.attr.colorSecondaryContainer))
            radius = resources.getDimension(R.dimen.cardCornerRadius)
            setContentPadding(dp(16), dp(16), dp(16), dp(16))
            addView(content)
        }
        form.addView(card)
        searchableCards += searchText.lowercase() to card
    }

    private fun applyFilter() {
        val q = filter.lowercase()
        val titleCard = form.findViewById<View>(R.id.titleCardView)
        titleCard.visibility = if (q.isBlank() || getString(R.string.remote_properties_remote_name).lowercase().contains(q)) View.VISIBLE else View.GONE
        searchableCards.forEach { (text, card) -> card.visibility = if (q.isBlank() || text.contains(q)) View.VISIBLE else View.GONE }
    }

    private fun save() {
        val portValue = port.text?.toString()?.toIntOrNull()
        when {
            remoteName.text.isNullOrBlank() -> showError(R.string.quarkdav_error_name_required)
            cookie.text.isNullOrBlank() -> showError(R.string.quarkdav_error_cookie_required)
            portValue == null || portValue !in 1..65535 -> showError(R.string.quarkdav_error_port)
            else -> {
                val remote = QuarkDavRemote(
                    id = original?.id ?: QuarkDavRemote().id,
                    name = remoteName.text.toString(),
                    enabled = enabled.isChecked,
                    startAtBoot = startAtBoot.isChecked,
                    keepCpuAwake = keepCpuAwake.isChecked,
                    listenAddress = listenAddress.text.toString(),
                    port = portValue,
                    prefix = prefix.text.toString(),
                    username = username.text.toString(),
                    password = password.text.toString(),
                    noAuth = noAuth.isChecked,
                    cacheTtlSeconds = cacheTtl.text?.toString()?.toIntOrNull() ?: 15,
                    tempDir = tempDir.text.toString(),
                    cookie = cookie.text.toString().trim(),
                    rootId = rootId.text.toString(),
                    brand = brand.text.toString(),
                    orderBy = orderBy.text.toString(),
                    orderDirection = orderDirection.text.toString(),
                    useTranscodingAddress = useTranscoding.isChecked,
                    onlyListVideoFile = onlyVideo.isChecked,
                )
                runCatching { QuarkDavRepository.save(requireContext(), remote) }
                    .onSuccess {
                        // Saving a disabled, never-running remote must not start a
                        // foreground service just to discover there is no work.
                        if (remote.enabled) QuarkDavServiceActions.startRemote(requireContext(), remote.id)
                        else if (original?.enabled == true) QuarkDavServiceActions.stopRemote(requireContext(), remote.id)
                        Toasty.success(requireContext(), getString(R.string.quarkdav_saved), Toast.LENGTH_SHORT, true).show()
                        requireActivity().setResult(Activity.RESULT_OK)
                        requireActivity().finish()
                    }
                    .onFailure {
                        Toasty.error(
                            requireContext(),
                            getString(R.string.quarkdav_error_save, it.message ?: it.javaClass.simpleName),
                            Toast.LENGTH_LONG,
                            true,
                        ).show()
                    }
            }
        }
    }

    private fun showError(resource: Int) {
        Toasty.error(requireContext(), getString(resource), Toast.LENGTH_LONG, true).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun resolveColor(attribute: Int): Int {
        val value = TypedValue()
        requireContext().theme.resolveAttribute(attribute, value, true)
        return value.data
    }
}
