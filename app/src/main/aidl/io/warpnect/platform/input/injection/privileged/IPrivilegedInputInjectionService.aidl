package io.warpnect.platform.input.injection.privileged;

import android.os.Bundle;

/** Internal synchronous Shizuku/Sui UserService contract. Do not mark hot methods oneway. */
interface IPrivilegedInputInjectionService {
    int getServiceVersion() = 0;
    Bundle getCapabilities() = 1;

    int prepare(int targetUid, int injectionMode, int maxTrackedSlots, int maxPressedKeys) = 2;
    int startInjection() = 3;
    int stopInjection(boolean resetAll) = 4;

    int injectKey(
        long sourceEventTimeUs, int stateSlot, int action, int keyCode, int repeatCount,
        int metaState, int scanCode, int flags, int source, int androidDeviceId, int displayId
    ) = 5;

    int injectTouch(
        long sourceEventTimeUs, int stateSlot, int actionMasked, int actionIndex, int pointerCount,
        in int[] pointerIds, in int[] toolTypes, in float[] xPx, in float[] yPx,
        in float[] pressure, in float[] size, int metaState, int buttonState, int source,
        int androidDeviceId, int displayId
    ) = 6;

    int injectPointer(
        long sourceEventTimeUs, int stateSlot, int action, int actionButton, float xPx, float yPx,
        float relativeXPx, float relativeYPx, float horizontalScroll, float verticalScroll,
        float pressure, float size, int metaState, int buttonState, int source,
        int androidDeviceId, int displayId
    ) = 7;

    int injectJoystick(
        long sourceEventTimeUs, int stateSlot, float leftX, float leftY, float rightX, float rightY,
        float leftTrigger, float rightTrigger, float hatX, float hatY, int metaState,
        int source, int androidDeviceId, int displayId
    ) = 8;

    int resetState(int scope, int stateSlot, int reason) = 9;
    Bundle getSnapshot() = 10;

    void destroy() = 16777114;
}
