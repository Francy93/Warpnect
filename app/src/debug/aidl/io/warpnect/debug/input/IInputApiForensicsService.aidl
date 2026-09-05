package io.warpnect.debug.input;

import android.os.Bundle;

/** Debug-only, read-mostly UserService contract for input API compatibility forensics. */
interface IInputApiForensicsService {
    Bundle inspect();
    Bundle injectTestKey(int displayId);
    Bundle injectTestTouch(int displayId, float xPx, float yPx);
    Bundle injectTestPointer(int displayId, float xPx, float yPx);
    Bundle injectTestJoystick(int displayId);
}
