package network.noth.messenger.libsession_util.util

object Logger {

    init {
        System.loadLibrary("session_util")
    }

    @JvmStatic
    external fun initLogger()
}