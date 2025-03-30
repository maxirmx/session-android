#include <jni.h>
#include <android/log.h>
#include <string_view>
#include <functional>

#include "session/logging.hpp"
#include "session/log_level.h"

#define LOG_TAG "LibSession"

extern "C" JNIEXPORT void JNICALL
Java_network_noth_messenger_libsession_1util_util_Logger_initLogger(JNIEnv* env, jclass clazz) {
    session::add_logger([](std::string_view msg, std::string_view category, session::LogLevel level) {
        android_LogPriority prio = ANDROID_LOG_VERBOSE;

        switch (level.level) {
            case LOG_LEVEL_TRACE:
                prio = ANDROID_LOG_VERBOSE;
                break;

            case LOG_LEVEL_DEBUG:
                prio = ANDROID_LOG_DEBUG;
                break;

            case LOG_LEVEL_INFO:
                prio = ANDROID_LOG_INFO;
                break;

            case LOG_LEVEL_WARN:
                prio = ANDROID_LOG_WARN;
                break;

            case LOG_LEVEL_ERROR:
            case LOG_LEVEL_CRITICAL:
                prio = ANDROID_LOG_ERROR;
                break;

            default:
                prio = ANDROID_LOG_INFO;
                break;
        }

        __android_log_print(prio, LOG_TAG, "%.*s [%.*s]",
                    static_cast<int>(msg.size()), msg.data(),
                    static_cast<int>(category.size()), category.data());
});
}