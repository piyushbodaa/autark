package com.piyushbodaa.autark.devicetest

/** Native side: tools/device-test/device_test.cpp, packaged in debug builds as libautark-tests.so. */
object DeviceTools {
    init {
        System.loadLibrary("autark-tests")
    }

    /**
     * The CPU backend variant's own verdict on this CPU: 0 if it can't run here, higher for more
     * features, or -1 if the app doesn't ship it.
     */
    external fun score(variant: String): Int

    /** Makes [variant] this process's CPU backend, before either tool starts. */
    external fun useVariant(variant: String): Boolean

    /** Runs tools/engine-test with [args], writing its output to [out] and [err]. Returns its exit code. */
    external fun engineTest(args: Array<String>, out: String, err: String): Int

    /** Runs llama.cpp's llama-bench the same way. */
    external fun llamaBench(args: Array<String>, out: String, err: String): Int
}
