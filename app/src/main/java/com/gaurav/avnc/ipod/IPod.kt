/*
 * iPod Touch emulator integration (QEMU-iOS running in Termux).
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.ipod

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.MotionEvent
import android.view.View
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.session.Messenger
import com.gaurav.avnc.vnc.XKeySym
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The emulator serves its screen over VNC on localhost and reads the hardware
 * buttons from the keyboard. The two emulators map them differently:
 *  - qemu-ios (devos50, "ipod" command): H = home, P = power, - / = = volume
 *  - qemu-ios-sam (Light Touch's): Command+Shift+H = home, Command+L = power,
 *    Command+- / Command+= = volume; plain letters are typed into iOS instead.
 * Each button presses one chord that works with both: qemu-ios ignores
 * Command, Shift and L, and qemu-ios-sam ignores P while Command is held.
 */
object IPod {
    const val HOST = "127.0.0.1"
    const val PORT = 5900

    const val TERMUX_PACKAGE = "com.termux"
    const val TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val TERMUX_SERVICE = "com.termux.app.RunCommandService"
    private const val TERMUX_ACTION = "com.termux.RUN_COMMAND"
    private const val BASH = "/data/data/com.termux/files/usr/bin/bash"

    /**
     * Runs the arguments with "device" (qemu-ios-sam, devices copied from Light
     * Touch) when a device is active there, else with "ipod" (qemu-ios).
     */
    private const val DISPATCH =
            "if command -v device >/dev/null && [ -e \"\$HOME/devices/current\" ]; " +
            "then exec device \"\$@\"; else exec ipod \"\$@\"; fi"
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"

    /** A key as keysym plus QEMU key number (the qnum QEMU's extended key event carries). */
    class Key(val keySym: Int, val xtCode: Int)

    private val COMMAND = Key(XKeySym.XK_Super_L, 0xdb)
    private val SHIFT = Key(XKeySym.XK_Shift_L, 0x2a)

    enum class Button(val keys: List<Key>) {
        Home(listOf(COMMAND, SHIFT, Key(XKeySym.XK_h, 0x23))),
        Power(listOf(COMMAND, Key(XKeySym.XK_l, 0x26), Key(XKeySym.XK_p, 0x19))),
        VolumeDown(listOf(COMMAND, Key(XKeySym.XK_minus, 0x0c))),
        VolumeUp(listOf(COMMAND, Key(XKeySym.XK_equal, 0x0d))),
    }

    fun createProfile() = ServerProfile(
            name = "iDevice",
            host = HOST,
            port = PORT,
            gestureStyle = "touchscreen",
            // The emulator runs on this phone: send the pixels uncompressed. With
            // Tight/JPEG, QEMU sends often-changing areas lossy, so scrolling and
            // page swipes showed JPEG artifacts, and the encoding cost CPU the
            // emulator needs.
            useRawEncoding = true,
    )

    fun isTermuxInstalled(context: Context) = runCatching {
        context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
    }.isSuccess

    /** Whether the emulator's VNC server accepts connections. */
    fun isRunning(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(HOST, PORT), 300) }
    }.isSuccess

    /** Runs "device <args>" or "ipod <args>" in the background through Termux's RUN_COMMAND service. */
    fun runCommand(context: Context, vararg args: String) {
        val intent = Intent(TERMUX_ACTION).apply {
            setClassName(TERMUX_PACKAGE, TERMUX_SERVICE)
            putExtra("com.termux.RUN_COMMAND_PATH", BASH)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", DISPATCH, "bash", *args))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        }
        if (Build.VERSION.SDK_INT >= 26)
            context.startForegroundService(intent)
        else
            context.startService(intent)
    }

    /** Press while touched, release when let go, like the real button. */
    @SuppressLint("ClickableViewAccessibility")
    fun bindButton(view: View, button: Button, messenger: () -> Messenger?) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    messenger()?.let { m -> button.keys.forEach { m.sendKey(it.keySym, it.xtCode, true) } }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    messenger()?.let { m -> button.keys.asReversed().forEach { m.sendKey(it.keySym, it.xtCode, false) } }
                    if (event.actionMasked == MotionEvent.ACTION_UP)
                        v.performClick()
                }
            }
            true
        }
    }
}
