package io.warpnect.platform.capture.privileged;

import android.os.Bundle;
import android.view.Surface;

interface IPrivilegedCaptureService {
    Bundle queryCapabilities();

    int startCapture(
        int sourceDisplayId,
        int outputWidth,
        int outputHeight,
        boolean followSourceRotation,
        in Surface targetSurface
    );

    int updateCapture(
        int sourceDisplayId,
        int outputWidth,
        int outputHeight,
        boolean followSourceRotation
    );

    int stopCapture();

    Bundle getState();
}
