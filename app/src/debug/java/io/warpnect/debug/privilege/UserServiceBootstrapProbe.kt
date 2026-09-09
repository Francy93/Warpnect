package io.warpnect.debug.privilege

import kotlin.system.exitProcess

/** A no-op Binder used only to isolate Shizuku UserService process bootstrap. */
class UserServiceBootstrapProbe : IUserServiceBootstrapProbe.Stub() {
    override fun destroy() {
        exitProcess(0)
    }
}
