package network.noth.messenger.libsession_util.util

import com.google.protobuf.Message

object Logger {

    init {
        try {

            //System.loadLibrary("session_util")
        }
        catch ( e: Exception ) {

        }
    }

    @JvmStatic
    external fun initLogger()
}