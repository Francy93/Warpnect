package io.warpnect.platform.input.injection.privileged;

import android.os.Bundle;

/** Internal synchronous Shizuku/Sui UserService contract. Do not mark hot methods oneway. */
interface IPrivilegedInputInjectionService {
    int getServiceVersion();
    Bundle getCapabilities();

    int prepare(int targetUid, int injectionMode, int maxTrackedSlots, int maxPressedKeys);
    int startInjection();
    int stopInjection(boolean resetAll);

    int injectKey(
        long sourceEventTimeUs, int stateSlot, int action, int keyCode, int repeatCount,
        int metaState, int scanCode, int flags, int source, int androidDeviceId, int displayId
    );

    int injectTouch(
        long sourceEventTimeUs, int stateSlot, int actionMasked, int actionIndex, int pointerCount,
        in int[] pointerIds, in int[] toolTypes, in float[] xPx, in float[] yPx,
        in float[] pressure, in float[] size, int metaState, int buttonState, int source,
        int androidDeviceId, int displayId
    );

    int injectPointer(
        long sourceEventTimeUs, int stateSlot, int action, int actionButton, float xPx, float yPx,
        float relativeXPx, float relativeYPx, float horizontalScroll, float verticalScroll,
        float pressure, float size, int metaState, int buttonState, int source,
        int androidDeviceId, int displayId
    );

    int injectJoystick(
        long sourceEventTimeUs, int stateSlot, float leftX, float leftY, float rightX, float rightY,
        float leftTrigger, float rightTrigger, float hatX, float hatY, int metaState,
        int source, int androidDeviceId, int displayId
    );

    int resetState(int scope, int stateSlot, int reason);
    Bundle getSnapshot();
}
