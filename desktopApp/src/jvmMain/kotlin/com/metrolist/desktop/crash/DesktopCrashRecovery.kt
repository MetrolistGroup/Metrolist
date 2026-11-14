package com.metrolist.desktop.crash

import com.metrolist.desktop.update.DesktopUpdateManager
import com.metrolist.desktop.utils.desktopRestartCommand
import com.metrolist.desktop.utils.prepareDesktopRelaunch
import com.metrolist.shared.BuildConfig
import com.metrolist.shared.crash.CrashReport
import com.metrolist.shared.crash.FatalCrashHandler
import com.metrolist.shared.crash.FileCrashStore
import com.metrolist.shared.crash.redactCrashReport
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.crash_close
import com.metrolist.shared.generated.resources.crash_copy_logs
import com.metrolist.shared.generated.resources.crash_imminent_message
import com.metrolist.shared.generated.resources.crash_imminent_title
import com.metrolist.shared.generated.resources.crash_let_it_crash
import com.metrolist.shared.generated.resources.crash_menu_reload
import com.metrolist.shared.generated.resources.crash_no_log
import com.metrolist.shared.generated.resources.crash_restart
import com.metrolist.shared.generated.resources.crash_share_logs
import com.metrolist.shared.generated.resources.crash_title
import com.metrolist.shared.generated.resources.crash_updates_action
import com.metrolist.shared.generated.resources.crash_updates_checking
import com.metrolist.shared.generated.resources.crash_updates_error
import com.metrolist.shared.generated.resources.crash_updates_no_updates
import com.metrolist.shared.utils.XdgUtils
import com.metrolist.shared.utils.openUrl
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import java.awt.BorderLayout
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.WindowConstants
import kotlin.system.exitProcess

object DesktopCrashRecovery {
    const val ARGUMENT = "--crash-report"
    val store get() = FileCrashStore(File(XdgUtils.getXdgDataHome(), "metrolist/crashes"))

    fun install() {
        val fallback =
            Thread.getDefaultUncaughtExceptionHandler() ?: Thread.UncaughtExceptionHandler { _, error ->
                System.err.println(redactCrashReport(error.stackTraceToString()))
                exitProcess(1)
            }
        Thread.setDefaultUncaughtExceptionHandler(
            FatalCrashHandler(fallback) { thread, error ->
                val report =
                    CrashReport.create(
                        BuildConfig.APP_VERSION,
                        "${System.getProperty(
                            "os.name",
                        )} ${System.getProperty("os.version")} ${System.getProperty("os.arch")} / ${thread.name}",
                        error,
                    )
                handoffDesktopCrash(report, store, ::prompt, ::desktopRestartCommand) { command ->
                    ProcessBuilder(command).prepareDesktopRelaunch().inheritIO().start()
                }
                Runtime.getRuntime().halt(1)
            },
        )
    }

    private fun label(resource: StringResource) = runBlocking { getString(resource) }

    private fun prompt(): DesktopCrashChoice =
        awaitDesktopCrashChoice(
            isHeadless = GraphicsEnvironment.isHeadless(),
            isDispatchThread = EventQueue.isDispatchThread(),
            dispatch = EventQueue::invokeLater,
        ) { onVisible ->
            val reload = label(Res.string.crash_menu_reload)
            val letItCrash = label(Res.string.crash_let_it_crash)
            val pane =
                JOptionPane(
                    label(Res.string.crash_imminent_message),
                    JOptionPane.ERROR_MESSAGE,
                    JOptionPane.DEFAULT_OPTION,
                    null,
                    arrayOf(reload, letItCrash),
                    letItCrash,
                )
            val dialog = pane.createDialog(null, label(Res.string.crash_imminent_title))
            try {
                dialog.addWindowListener(
                    object : WindowAdapter() {
                        override fun windowOpened(event: WindowEvent) {
                            if (!onVisible()) dialog.dispose()
                        }
                    },
                )
                dialog.isVisible = true
                if (pane.value == reload) DesktopCrashChoice.RELOAD else DesktopCrashChoice.LET_IT_CRASH
            } finally {
                dialog.dispose()
            }
        }

    fun show(id: String) {
        store.acknowledge(id)
        val report = store.list().firstOrNull { it.id == id }?.text ?: label(Res.string.crash_no_log)
        EventQueue.invokeLater {
            val frame = JFrame(label(Res.string.crash_title))
            frame.defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
            frame.add(
                JScrollPane(
                    JTextArea(report).apply {
                        isEditable = false
                        caretPosition = 0
                    },
                ),
                BorderLayout.CENTER,
            )
            val actions = JPanel()

            fun action(
                resource: StringResource,
                block: () -> Unit,
            ): JButton =
                JButton(label(resource)).also {
                    it.addActionListener { block() }
                    actions.add(it)
                }
            action(Res.string.crash_copy_logs) { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(report), null) }
            action(Res.string.crash_share_logs) { share(report) }
            action(Res.string.crash_restart) {
                ProcessBuilder(desktopRestartCommand()).prepareDesktopRelaunch().inheritIO().start()
                exitProcess(0)
            }
            action(Res.string.crash_close) { exitProcess(0) }
            val update = action(Res.string.crash_updates_checking) {}
            update.isEnabled = false
            frame.add(actions, BorderLayout.SOUTH)
            frame.setSize(900, 650)
            frame.setLocationRelativeTo(null)
            frame.isVisible = true
            Thread({
                val result = runBlocking { DesktopUpdateManager.checkForRecovery() }
                EventQueue.invokeLater {
                    val release = result.getOrNull()
                    update.text =
                        when {
                            result.isFailure -> label(Res.string.crash_updates_error)
                            release == null -> label(Res.string.crash_updates_no_updates)
                            else -> "${label(Res.string.crash_updates_action)} ·\u00a0${release.version}"
                        }
                    update.isEnabled = release != null
                    if (release != null) {
                        update.addActionListener {
                            update.isEnabled = false
                            Thread({
                                val installed =
                                    release.inApp && runBlocking { DesktopUpdateManager.downloadAndLaunch(release.version).isSuccess }
                                if (installed) exitProcess(0)
                                openUrl(release.url)
                                EventQueue.invokeLater { update.isEnabled = true }
                            }, "CrashUpdate").start()
                        }
                    }
                }
            }, "CrashUpdateCheck").start()
        }
    }

    fun share(report: String) {
        val chooser = JFileChooser().apply { selectedFile = File("metrolist-crash.txt") }
        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.writeText(report)
    }
}
