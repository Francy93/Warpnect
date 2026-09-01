package io.warpnect.platform.capture.experimental;

import android.os.Bundle;
import android.os.IBinder;

/** Debug-only app-process owner for a bounded codec-to-privileged-mirror experiment. */
interface IExperimentalAppCodecService {
    Bundle runSplitProcessLegacyFrame(IBinder mirrorService, boolean secure);
}
