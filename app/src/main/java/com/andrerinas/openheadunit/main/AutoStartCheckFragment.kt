package com.andrerinas.openheadunit.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.app.AutoStartCheckPolicy
import com.andrerinas.openheadunit.app.AutoStartCheckPolicy.Verdict
import com.andrerinas.openheadunit.main.settings.SettingItem
import com.andrerinas.openheadunit.main.settings.SettingsAdapter
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.AutoStartCheck
import com.andrerinas.openheadunit.utils.OemAppManager
import com.andrerinas.openheadunit.utils.ToastUtils
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * Read-only check of why the head unit may be closing the app (#1122). Asks for root when opened,
 * so units with a root manager show its prompt here, and nothing on the unit is changed.
 */
class AutoStartCheckFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private val adapter = SettingsAdapter()
    private var findings: AutoStartCheckPolicy.Findings? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_auto_start, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setTitle(R.string.auto_start_check_title)
        toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        recyclerView = view.findViewById(R.id.recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        if (findings == null) runCheck() else updateList()
    }

    private fun runCheck() {
        findings = null
        updateList()
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            OemAppManager.requestRoot()
            findings = try {
                AutoStartCheck.run(appContext)
            } catch (e: Exception) {
                AppLog.e("AutoStartCheckFragment: check failed: ${e.message}", e)
                null
            }
            updateList()
        }
    }

    private fun updateList() {
        val items = mutableListOf<SettingItem>()
        items.add(SettingItem.InfoBanner("checkIntro", R.string.auto_start_check_intro))

        val f = findings
        if (f == null) {
            items.add(SettingItem.InfoBanner("checkRunning", R.string.auto_start_check_running))
            adapter.submitList(items)
            return
        }

        items.add(SettingItem.CategoryHeader("checkResult", R.string.auto_start_check_result))
        items.add(SettingItem.InfoBanner("checkVerdict", verdictRes(AutoStartCheckPolicy.verdict(f))))

        items.add(SettingItem.CategoryHeader("checkDetails", R.string.auto_start_check_details))
        items.add(row("checkAccess", R.string.auto_start_check_access, getString(when (f.route) {
            OemAppManager.ExecutionBackend.ROOT.name.lowercase() -> R.string.auto_start_check_access_root
            OemAppManager.ExecutionBackend.ADB.name.lowercase() -> R.string.auto_start_check_access_adb
            else -> R.string.auto_start_check_access_none
        })))
        items.add(row("checkDevice", R.string.auto_start_check_device, "${f.device} · Android ${f.android}"))
        items.add(row("checkPlatform", R.string.auto_start_check_platform, "${f.platform} · ${f.firmware}"))
        items.add(row("checkHome", R.string.auto_start_check_home_app, yesNo(f.isHomeApp)))
        items.add(row("checkBattery", R.string.auto_start_check_battery, when (f.batteryExempt) {
            true -> getString(R.string.auto_start_check_battery_exempt)
            false -> getString(R.string.auto_start_check_battery_not_exempt)
            null -> getString(R.string.auto_start_check_unknown)
        }))
        items.add(row("checkDuraSpeed", R.string.auto_start_check_duraspeed, when (f.duraSpeed) {
            true -> getString(R.string.auto_start_check_installed)
            false -> getString(R.string.auto_start_check_not_installed)
            null -> getString(R.string.auto_start_check_unknown)
        }))
        items.add(row("checkCloser", R.string.auto_start_check_closer,
            f.stoppedBy.joinToString(", ").ifEmpty {
                // Without root or ADB the log was not read, so an empty result proves nothing.
                val logRead = f.route != OemAppManager.ExecutionBackend.NONE.name.lowercase()
                getString(if (logRead && f.forceStops.isEmpty()) R.string.auto_start_check_closer_none
                else R.string.auto_start_check_unknown)
            }))
        items.add(SettingItem.SettingEntry(
            stableId = "checkLists",
            nameResId = R.string.auto_start_check_lists,
            value = getString(R.string.auto_start_check_lists_value,
                f.listFiles.size, f.listFiles.count { it.hasApp }),
            onClick = { showLists(f) }
        ))

        items.add(SettingItem.ActionButton("checkCopy", R.string.auto_start_check_copy) { copyReport(f) })
        items.add(SettingItem.ActionButton("checkShare", R.string.auto_start_check_share) { shareReport(f) })
        items.add(SettingItem.ActionButton("checkAgain", R.string.auto_start_check_again) { runCheck() })

        adapter.submitList(items)
    }

    private fun row(id: String, nameResId: Int, value: String) =
        SettingItem.SettingEntry(stableId = id, nameResId = nameResId, value = value, onClick = {})

    private fun yesNo(value: Boolean): String =
        getString(if (value) R.string.auto_start_check_yes else R.string.auto_start_check_no)

    private fun verdictRes(verdict: Verdict): Int = when (verdict) {
        Verdict.FORCE_STOPPED -> R.string.auto_start_check_verdict_force_stopped
        Verdict.BATTERY -> R.string.auto_start_check_verdict_battery
        Verdict.UNKNOWN -> R.string.auto_start_check_verdict_unknown
    }

    private fun showLists(f: AutoStartCheckPolicy.Findings) {
        if (f.listFiles.isEmpty()) return
        val text = f.listFiles.joinToString("\n") { (if (it.hasApp) "✓ " else "· ") + it.path }
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.auto_start_check_lists)
            .setMessage(text)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun copyReport(f: AutoStartCheckPolicy.Findings) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.auto_start_check_title), AutoStartCheckPolicy.report(f)))
        ToastUtils.showToast(context, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT, force = true)
    }

    private fun shareReport(f: AutoStartCheckPolicy.Findings) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, AutoStartCheckPolicy.report(f))
        }
        try {
            startActivity(Intent.createChooser(send, getString(R.string.auto_start_check_share)))
        } catch (e: Exception) {
            AppLog.w("AutoStartCheckFragment: no app to share with: ${e.message}")
            copyReport(f)
        }
    }
}
