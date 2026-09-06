package io.warpnect.platform.capture.privileged;

import android.os.Bundle;
import android.view.Surface;

interface IPrivilegedCaptureService {
    Bundle queryCapabilities() = 0;

    int startCapture(
        int sourceDisplayId,
        int outputWidth,
        int outputHeight,
        boolean followSourceRotation,
        in Surface targetSurface
    ) = 1;

    int updateCapture(
        int sourceDisplayId,
        int outputWidth,
        int outputHeight,
        boolean followSourceRotation
    ) = 2;

    int stopCapture() = 3;

    Bundle getState() = 4;

    void destroy() = 16777114;
}
