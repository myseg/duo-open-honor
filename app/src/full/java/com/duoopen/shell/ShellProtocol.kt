package com.duoopen.shell

/** Binder protocol between the app and [DuoShellService] (runs with Shizuku's ADB privileges). */
object ShellProtocol {
    const val TOKEN = "com.duoopen.shell.DuoShellService"
    const val CALLBACK_TOKEN = "com.duoopen.shell.AngleCallback"

    /** → Bundle{uid, pid, version} */
    const val PING = 1
    /** in: displayId, n, SurfaceControl×n (excluded), scale → Bundle{ok, bitmap, width, height, error, secure} */
    const val CAPTURE = 2
    /** in: wallpaper command action ("" for none), callback IBinder → starts the Samsung hinge log reader */
    const val START_ANGLES = 3
    const val STOP_ANGLES = 4
    /** → Bundle{state, lines, parsed, rejected, last, angle, hal, wallpaper} */
    const val ANGLE_STATUS = 5

    /** callback: float angle, long uptimeMs when the reading was taken, int SOURCE_* */
    const val CB_ANGLE = 1

    /** Which hinge sensor a reading came from (same values as HingeTracker's). */
    const val SOURCE_FOLDING = 0
    const val SOURCE_LID = 1
    /** Samsung's Fold interactive wallpaper (1°, on every command we send it). */
    const val SOURCE_WALLPAPER = 2
    /** Not a reading: the hall sensor says the phone has just shut (angle 0) or opened. */
    const val SOURCE_SHUT = 3
    const val SOURCE_UNSHUT = 4
}
