package com.nmaclean.tvheadend

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import androidx.leanback.widget.GuidedActionsStylist
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nmaclean.tvheadend.model.TvhConstants
import com.nmaclean.tvheadend.model.TvhTifSettings
import com.nmaclean.tvheadend.sync.TvhSyncManager
import com.nmaclean.tvheadend.sync.TvhSyncWorker
import com.nmaclean.tvheadend.ui.MainActivity
import kotlin.concurrent.thread

class TvhWelcomeFragment : GuidedStepSupportFragment() {
    companion object {
        private const val ID_START = 1L
        private const val ID_DISABLE = 2L
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        val title = "Tvheadend TIF Setup"
        val settings = TvhTifSettings(requireContext())
        val serverSummary = if (settings.host.isNotBlank()) {
            val userPart = if (settings.username.isNotBlank()) " • User: ${settings.username}" else ""
            "\n\nCurrent server: ${settings.host}:${settings.htspPort} (HTTP ${settings.httpPort})$userPart"
        } else {
            "\n\nNo server configured yet."
        }
        val description = "Connect your Android TV to your Tvheadend server to stream live TV channels, program guides (EPG), and channel logos.$serverSummary\n\nVersion: v${BuildConfig.VERSION_NAME} • Build: ${BuildConfig.BUILD_TIME}"
        val breadcrumb = "TV Input Setup"
        return GuidanceStylist.Guidance(title, description, breadcrumb, null)
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions.add(
            GuidedAction.Builder(activity)
                .id(ID_START)
                .title("Start")
                .description("Configure server connection and import channels")
                .build()
        )
        actions.add(
            GuidedAction.Builder(activity)
                .id(ID_DISABLE)
                .title("Disable")
                .description("Keep settings, remove channel list & refresh")
                .build()
        )
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_START -> add(parentFragmentManager, TvhCredentialsFragment())
            ID_DISABLE -> {
                val ctx = requireContext().applicationContext
                TvhSetupActions.disableAndRefresh(ctx)
                Toast.makeText(activity, "Settings kept • Channel list removed & refreshing", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

internal object TvhSetupActions {
    fun setTvheadendTifEnabled(appContext: android.content.Context, enabled: Boolean) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(appContext)
        prefs.edit().putBoolean("tvheadend_tif_enabled", enabled).apply()
        if (!enabled) {
            com.nmaclean.tvheadend.htsp.ChannelRegistry.updateChannels(emptyList())
            TvhSyncManager.clearAllChannelsAndPrograms(appContext)
            com.nmaclean.tvheadend.ui.TvhUiRepository.invalidateCaches()
        }
    }

    fun disableAndRefresh(appContext: android.content.Context) {
        setTvheadendTifEnabled(appContext, false)
        val hasHost = false
        if (hasHost) {
            thread(name = "tvh-disable-refresh") {
                try {
                    TvhSyncManager.performSync(appContext)
                } catch (_: Throwable) {
                }
            }
        }
    }
}

class TvhCredentialsFragment : GuidedStepSupportFragment() {

    companion object {
        private const val TAG = "TvhCredentialsFragment"
        private const val ID_HOST = 1L
        private const val ID_PORT = 2L
        private const val ID_HTTP_PORT = 3L
        private const val ID_USER = 4L
        private const val ID_PASS = 5L
        private const val ID_TEST_CONNECTION = 6L
        private const val ID_SAVE = 7L
        private const val ID_CLEAR = 8L
        private const val ID_BACK = 9L
        private const val ID_DISABLE = 12L
        private const val ID_FASTSTART = 10L
        private const val ID_TIMESHIFT = 11L
    }

    private var hostValue = ""
    private var htspPortValue = TvhConstants.DEFAULT_HTSP_PORT
    private var httpPortValue = TvhConstants.DEFAULT_HTTP_PORT
    private var usernameValue = ""
    private var passwordValue = ""
    private var faststartValue = true
    private var timeshiftValue = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadSettingsIntoFields()
    }

    override fun onResume() {
        super.onResume()
        loadSettingsIntoFields()
        refreshActionValues()
    }

    private fun loadSettingsIntoFields() {
        val settings = TvhTifSettings(requireContext())
        hostValue = settings.host
        htspPortValue = if (settings.htspPort > 0) settings.htspPort else TvhConstants.DEFAULT_HTSP_PORT
        httpPortValue = if (settings.httpPort > 0) settings.httpPort else TvhConstants.DEFAULT_HTTP_PORT
        usernameValue = settings.username
        passwordValue = settings.password
        faststartValue = settings.isFaststartEnabled
        timeshiftValue = settings.isTimeshiftEnabled
    }

    private fun refreshActionValues() {
        val updates = listOf(
            ID_HOST to Pair(hostValue, TvhSetupInputValidator.formatHostDescription(hostValue)),
            ID_PORT to Pair(htspPortValue.toString(), TvhSetupInputValidator.formatPortDescription(htspPortValue, TvhConstants.DEFAULT_HTSP_PORT, "HTSP")),
            ID_HTTP_PORT to Pair(httpPortValue.toString(), TvhSetupInputValidator.formatPortDescription(httpPortValue, TvhConstants.DEFAULT_HTTP_PORT, "HTTP")),
            ID_USER to Pair(usernameValue, TvhSetupInputValidator.formatUsernameDescription(usernameValue)),
            ID_PASS to Pair(passwordValue, TvhSetupInputValidator.formatPasswordDescription(passwordValue))
        )
        for ((id, pair) in updates) {
            val idx = findActionPositionById(id)
            if (idx >= 0) {
                actions[idx].editTitle = pair.first
                actions[idx].description = pair.second
                notifyActionChanged(idx)
            }
        }
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        val title = "Server Credentials"
        val description = "Enter your server IP address, HTSP port, and optional authentication credentials."
        val breadcrumb = "TV Input Setup"
        return GuidanceStylist.Guidance(title, description, breadcrumb, null)
    }

    override fun onCreateActionsStylist(): GuidedActionsStylist {
        return object : GuidedActionsStylist() {
            override fun onBindViewHolder(vh: ViewHolder, action: GuidedAction) {
                super.onBindViewHolder(vh, action)
                val noExtractFlags = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
                vh.editableTitleView?.let { edit ->
                    edit.imeOptions = edit.imeOptions or noExtractFlags
                }
                vh.editableDescriptionView?.let { edit ->
                    edit.imeOptions = edit.imeOptions or noExtractFlags
                }
            }
        }
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions.add(
            GuidedAction.Builder(activity)
                .id(ID_HOST)
                .title("Server IP / Host")
                .editTitle(hostValue)
                .description(TvhSetupInputValidator.formatHostDescription(hostValue))
                .editable(true)
                .editInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
                .build()
        )

        actions.add(
            GuidedAction.Builder(activity)
                .id(ID_PORT)
                .title("HTSP Port")
                .editTitle(htspPortValue.toString())
                .description(TvhSetupInputValidator.formatPortDescription(htspPortValue, TvhConstants.DEFAULT_HTSP_PORT, "HTSP"))
                .editable(true)
                .editInputType(InputType.TYPE_CLASS_NUMBER)
                .build()
        )

        actions.add(
            GuidedAction.Builder(activity)
                .id(ID_HTTP_PORT)
                .title("HTTP Stream Port")
                .editTitle(httpPortValue.toString())
                .description(TvhSetupInputValidator.formatPortDescription(httpPortValue, TvhConstants.DEFAULT_HTTP_PORT, "HTTP"))
                .editable(true)
                .editInputType(InputType.TYPE_CLASS_NUMBER)
                .build()
        )

        actions.add(
            GuidedAction.Builder(activity)
                .id(ID_USER)
                .title("Username")
                .editTitle(usernameValue)
                .description(TvhSetupInputValidator.formatUsernameDescription(usernameValue))
                .editable(true)
                .editInputType(InputType.TYPE_CLASS_TEXT)
                .build()
        )

        actions.add(
            GuidedAction.Builder(activity)
                .id(ID_PASS)
                .title("Password")
                .editTitle(passwordValue)
                .description(TvhSetupInputValidator.formatPasswordDescription(passwordValue))
                .editable(true)
                .editInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
                .build()
        )

        actions.add(
            GuidedAction.Builder(activity)
                .id(ID_FASTSTART)
                .title("HTSP Faststart")
                .description(if (faststartValue) "Enabled (immediate playback start)" else "Disabled")
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(faststartValue)
                .build()
        )

        actions.add(
            GuidedAction.Builder(activity)
                .id(ID_TIMESHIFT)
                .title("HTSP Timeshift")
                .description(if (timeshiftValue) "Enabled (allow pause/rewind)" else "Disabled")
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(timeshiftValue)
                .build()
        )

        actions.add(
            GuidedAction.Builder(activity)
                .id(ID_TEST_CONNECTION)
                .title("Test Server Connection")
                .description("Verify HTSP authentication and HTTP stream reachability")
                .build()
        )

        actions.add(
            GuidedAction.Builder(activity)
                .id(ID_SAVE)
                .title("Save & Start Scanning")
                .description("Save settings and import channels & EPG")
                .build()
        )

        actions.add(
            GuidedAction.Builder(activity)
                .id(ID_DISABLE)
                .title("Disable")
                .description("Keep settings, remove channel list & refresh")
                .build()
        )

        actions.add(
            GuidedAction.Builder(activity)
                .id(ID_CLEAR)
                .title("Clear Settings")
                .description("Reset all fields to default values")
                .build()
        )

        actions.add(
            GuidedAction.Builder(activity)
                .id(ID_BACK)
                .title("Back")
                .description("Return to welcome screen")
                .build()
        )
    }

    private data class FormValues(
        val host: String,
        val htspPort: Int,
        val httpPort: Int,
        val username: String,
        val password: String
    )

    private fun readFormValues(): FormValues =
        FormValues(hostValue, htspPortValue, httpPortValue, usernameValue, passwordValue)

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id == ID_BACK) {
            requireActivity().onBackPressedDispatcher.onBackPressed()
            return
        }

        if (action.id == ID_DISABLE) {
            val ctx = requireContext().applicationContext
            TvhSetupActions.disableAndRefresh(ctx)
            Toast.makeText(activity, "Settings kept • Channel list removed & refreshing", Toast.LENGTH_SHORT).show()
            return
        }

        if (action.id == ID_CLEAR) {
            TvhTifSettings(requireContext()).clear()
            TvhSyncManager.clearAllChannelsAndPrograms(requireContext())
            Toast.makeText(activity, "Settings & Guide cleared", Toast.LENGTH_SHORT).show()
            parentFragmentManager.beginTransaction()
                .replace(R.id.setup_fragment_container, TvhCredentialsFragment())
                .commit()
            return
        }

        if (action.id == ID_TEST_CONNECTION) {
            val values = readFormValues()
            val testIdx = findActionPositionById(ID_TEST_CONNECTION)
            if (testIdx >= 0) {
                actions[testIdx].description = "Testing ${values.host}:${values.htspPort}..."
                notifyActionChanged(testIdx)
            }

            thread {
                val res = TvhSyncManager.testServerConnection(
                    host = values.host,
                    htspPort = values.htspPort,
                    httpPort = values.httpPort,
                    username = values.username,
                    password = values.password
                )
                if (!isAdded) return@thread
                requireActivity().runOnUiThread {
                    val idx = findActionPositionById(ID_TEST_CONNECTION)
                    if (idx >= 0) {
                        actions[idx].description = res.message
                        notifyActionChanged(idx)
                    }
                }
            }
            return
        }

        if (action.id == ID_SAVE) {
            val values = readFormValues()
            Log.d(TAG, "Saving settings - Host: '${values.host}', Port: ${values.htspPort}, HTTP Port: ${values.httpPort}, User: '${values.username}'")

            val settings = TvhTifSettings(requireContext())
            settings.host = values.host
            settings.htspPort = values.htspPort
            settings.httpPort = values.httpPort
            settings.username = values.username
            settings.password = values.password

            val resultFragment = TvhSetupResultFragment.newInstance(isScanning = true)
            add(parentFragmentManager, resultFragment)
        }
    }

    override fun onGuidedActionEditedAndProceed(action: GuidedAction): Long {
        applyEditedAction(action)
        return GuidedAction.ACTION_ID_CURRENT
    }

    override fun onGuidedActionEditCanceled(action: GuidedAction) {
        applyEditedAction(action)
    }

    private fun applyEditedAction(action: GuidedAction) {
        val index = findActionPositionById(action.id)
        if (index < 0) return

        val trimmed = action.editTitle?.toString()?.trim().orEmpty()
        when (action.id) {
            ID_HOST -> {
                hostValue = TvhSetupInputValidator.normalizeHost(trimmed)
                action.editTitle = hostValue
                action.description = TvhSetupInputValidator.formatHostDescription(hostValue)
            }
            ID_PORT -> {
                htspPortValue = TvhSetupInputValidator.normalizePort(trimmed, TvhConstants.DEFAULT_HTSP_PORT)
                action.editTitle = htspPortValue.toString()
                action.description = htspPortValue.toString()
            }
            ID_HTTP_PORT -> {
                httpPortValue = TvhSetupInputValidator.normalizePort(trimmed, TvhConstants.DEFAULT_HTTP_PORT)
                action.editTitle = httpPortValue.toString()
                action.description = httpPortValue.toString()
            }
            ID_USER -> {
                usernameValue = TvhSetupInputValidator.normalizeOptionalText(trimmed, "Optional username")
                action.editTitle = usernameValue
                action.description = TvhSetupInputValidator.formatUsernameDescription(usernameValue)
            }
            ID_PASS -> {
                passwordValue = TvhSetupInputValidator.resolvePassword(trimmed, passwordValue, "Optional password")
                action.editTitle = passwordValue
                action.description = TvhSetupInputValidator.formatPasswordDescription(passwordValue)
            }
            ID_FASTSTART -> {
                faststartValue = action.isChecked
                TvhTifSettings(requireContext()).isFaststartEnabled = faststartValue
                action.description = if (faststartValue) "Enabled (immediate playback start)" else "Disabled"
            }
            ID_TIMESHIFT -> {
                timeshiftValue = action.isChecked
                TvhTifSettings(requireContext()).isTimeshiftEnabled = timeshiftValue
                action.description = if (timeshiftValue) "Enabled (allow pause/rewind)" else "Disabled"
            }
        }
        notifyActionChanged(index)
    }
}

class TvhSetupResultFragment : GuidedStepSupportFragment() {
    companion object {
        private const val ARG_IS_SCANNING = "arg_is_scanning"
        private const val ARG_CHANNELS = "arg_channels"
        private const val ARG_LOGOS = "arg_logos"
        private const val ARG_PROGRAMS = "arg_programs"
        private const val ID_FINISH = 100L
        private const val ID_BACK = 101L

        fun newInstance(isScanning: Boolean, channels: Int = 0, logos: Int = 0, programs: Int = 0): TvhSetupResultFragment {
            val fragment = TvhSetupResultFragment()
            val args = Bundle().apply {
                putBoolean(ARG_IS_SCANNING, isScanning)
                putInt(ARG_CHANNELS, channels)
                putInt(ARG_LOGOS, logos)
                putInt(ARG_PROGRAMS, programs)
            }
            fragment.arguments = args
            return fragment
        }
    }

    private var isScanning = true
    private var channelCount = 0
    private var logoCount = 0
    private var programCount = 0
    private var syncStatusLineView: TextView? = null
    private var previewRecyclerView: RecyclerView? = null
    private val previewAdapter = ChannelLogoPreviewAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            isScanning = it.getBoolean(ARG_IS_SCANNING, true)
            channelCount = it.getInt(ARG_CHANNELS, 0)
            logoCount = it.getInt(ARG_LOGOS, 0)
            programCount = it.getInt(ARG_PROGRAMS, 0)
        }
    }

    override fun onCreateGuidanceStylist(): GuidanceStylist {
        return object : GuidanceStylist() {
            override fun onProvideLayoutId(): Int = R.layout.guidance_setup_scan

            override fun onCreateView(
                inflater: LayoutInflater,
                container: ViewGroup?,
                guidance: Guidance?
            ): View {
                val root = super.onCreateView(inflater, container, guidance)
                syncStatusLineView = root.findViewById(R.id.sync_status_line)
                previewRecyclerView = root.findViewById<RecyclerView>(R.id.channel_logo_preview_list)?.apply {
                    layoutManager = LinearLayoutManager(requireContext())
                    adapter = previewAdapter
                }
                return root
            }
        }
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        val title = if (isScanning) "Scanning Channels" else "Setup Complete"
        val description = if (isScanning) {
            "Connecting to Tvheadend server and importing channels & logos..."
        } else {
            "Imported $channelCount channels • $logoCount logos loaded • $programCount EPG programs.\nSelect Done below to open Live Channels."
        }
        val breadcrumb = "TV Input Setup"
        return GuidanceStylist.Guidance(title, description, breadcrumb, null)
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        if (isScanning) {
            actions.add(
                GuidedAction.Builder(activity)
                    .id(ID_BACK)
                    .title("Cancel / Back")
                    .description("Return to server credentials")
                    .build()
            )
        } else {
            actions.add(
                GuidedAction.Builder(activity)
                    .id(ID_FINISH)
                    .title("Done / Open Live Channels")
                    .description("Complete setup and browse imported channels")
                    .build()
            )
            actions.add(
                GuidedAction.Builder(activity)
                    .id(ID_BACK)
                    .title("Back")
                    .description("Modify server settings or re-sync")
                    .build()
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (isScanning) {
            startBackgroundSync()
        } else {
            val finishIndex = findActionPositionById(ID_FINISH)
            if (finishIndex >= 0) {
                setSelectedActionPosition(finishIndex)
            }
        }
    }

    private var syncStarted = false

    @SuppressLint("SetTextI18n")
    private fun startBackgroundSync() {
        if (syncStarted) return
        syncStarted = true

        var lastProgressUiUpdateMs = 0L

        thread {
            val result = TvhSyncManager.performSync(requireContext()) { progress ->
                if (!isAdded) return@performSync
                val now = System.currentTimeMillis()
                if (now - lastProgressUiUpdateMs > 150L || progress.importedChannels == progress.totalChannels) {
                    lastProgressUiUpdateMs = now
                    requireActivity().runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        guidanceStylist?.descriptionView?.text = TvhSetupInputValidator.formatProgressSummary(
                            progress.importedChannels,
                            progress.totalChannels,
                            progress.logosLoaded
                        )
                        if (progress.currentChannelName.isNotEmpty()) {
                            syncStatusLineView?.text = "Syncing #${progress.currentChannelNumber} ${progress.currentChannelName}..."
                        }
                        progress.latestPreviewItem?.let { item ->
                            val insertPos = previewAdapter.upsertItem(item)
                            if (isScanning && insertPos >= 0) {
                                previewRecyclerView?.scrollToPosition(insertPos)
                            }
                        }
                    }
                }
            }

            if (!isAdded) return@thread

            requireActivity().runOnUiThread {
                if (result.success) {
                    Toast.makeText(activity, "Import successful!", Toast.LENGTH_SHORT).show()
                    isScanning = false
                    channelCount = result.channelCount
                    logoCount = result.logoCount
                    programCount = result.programCount

                    val newActions = mutableListOf<GuidedAction>()
                    newActions.add(
                        GuidedAction.Builder(activity)
                            .id(ID_FINISH)
                            .title("Done / Open Live Channels")
                            .description("Complete setup and browse imported channels")
                            .build()
                    )
                    newActions.add(
                        GuidedAction.Builder(activity)
                            .id(ID_BACK)
                            .title("Back")
                            .description("Modify server settings or re-sync")
                            .build()
                    )
                    setActions(newActions)

                    val finishIndex = findActionPositionById(ID_FINISH)
                    if (finishIndex >= 0) {
                        setSelectedActionPosition(finishIndex)
                    }

                    guidanceStylist?.titleView?.text = "Setup Complete"
                    guidanceStylist?.descriptionView?.text =
                        "Imported $channelCount channels • $logoCount logos loaded • $programCount EPG programs."
                    syncStatusLineView?.text = "Use D-pad Left to browse the imported channel & logo list."
                } else {
                    Toast.makeText(activity, "Error: ${result.error}", Toast.LENGTH_LONG).show()
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id == ID_FINISH) {
            val syncIntent = Intent(requireContext(), SyncService::class.java)
            requireContext().startService(syncIntent)
            TvhSyncWorker.schedulePeriodicSync(requireContext())

            requireActivity().setResult(Activity.RESULT_OK)
            if (requireActivity().callingActivity == null) {
                startActivity(Intent(requireContext(), MainActivity::class.java))
            }
            requireActivity().finish()
        } else if (action.id == ID_BACK) {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }
}

/**
 * RecyclerView adapter displaying live channel numbers, names, and loaded picon thumbnails
 * (with a fallback badge when no logo URL exists or logo fetching fails).
 */
private class ChannelLogoPreviewAdapter :
    RecyclerView.Adapter<ChannelLogoPreviewAdapter.PreviewViewHolder>() {

    private val items = mutableListOf<TvhSyncManager.ChannelLogoPreviewItem>()

    fun upsertItem(item: TvhSyncManager.ChannelLogoPreviewItem): Int {
        val existingIdx = items.indexOfFirst { it.channelId == item.channelId }
        return if (existingIdx >= 0) {
            items[existingIdx] = item
            notifyItemChanged(existingIdx)
            existingIdx
        } else {
            items.add(item)
            val newIdx = items.size - 1
            notifyItemInserted(newIdx)
            newIdx
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PreviewViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel_logo_preview, parent, false)
        return PreviewViewHolder(view)
    }

    override fun onBindViewHolder(holder: PreviewViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class PreviewViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val logoImage: ImageView = itemView.findViewById(R.id.logo_image)
        private val fallbackBadge: TextView = itemView.findViewById(R.id.logo_fallback_badge)
        private val channelNumber: TextView = itemView.findViewById(R.id.channel_number)
        private val channelName: TextView = itemView.findViewById(R.id.channel_name)
        private val logoStatus: TextView = itemView.findViewById(R.id.logo_status)

        fun bind(item: TvhSyncManager.ChannelLogoPreviewItem) {
            channelNumber.text = if (item.number > 0) "#${item.number}" else "-"
            channelName.text = item.name

            if (item.logoBitmap != null) {
                logoImage.setImageBitmap(item.logoBitmap)
                logoImage.visibility = View.VISIBLE
                fallbackBadge.visibility = View.GONE
                logoStatus.text = "Logo OK"
            } else {
                logoImage.setImageDrawable(null)
                logoImage.visibility = View.GONE
                fallbackBadge.visibility = View.VISIBLE
                val initials = item.name
                    .split(Regex("\\s+"))
                    .filter { it.isNotBlank() }
                    .take(2)
                    .joinToString("") { it.first().uppercaseChar().toString() }
                    .ifEmpty { "TV" }
                fallbackBadge.text = initials
                logoStatus.text = when {
                    !item.hasLogoUrl -> "No logo"
                    item.logoFailed -> "No logo (load failed)"
                    else -> "Loading..."
                }
            }
        }
    }
}
